package com.opensmpsdeck.project;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watches the containing directory so atomic file replacement remains visible.
 * Callbacks run on this object's worker, never on the constructing/UI thread.
 * A bounded periodic fingerprint also covers missed/overflowed watch events.
 */
public final class ProjectWatcher implements AutoCloseable {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final long DEBOUNCE_NANOS = TimeUnit.MILLISECONDS.toNanos(75);
    private static final long PROBE_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private final Path file;
    private final Runnable callback;
    private final WatchService service;
    private final Thread worker;
    private volatile boolean closed;
    private String lastFingerprint;

    public ProjectWatcher(Path path, Runnable callback) throws IOException {
        this.file = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.callback = Objects.requireNonNull(callback, "callback");
        if (file.getFileName() == null) throw new IOException("A project filename is required");
        this.service = file.getFileSystem().newWatchService();
        try {
            this.lastFingerprint = fingerprint();
            file.getParent().register(service, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException | RuntimeException exception) {
            service.close();
            throw exception;
        }
        this.worker = new Thread(this::watch, "opensmps-project-watch-" + NEXT_ID.incrementAndGet());
        worker.setDaemon(true);
        worker.start();
    }

    private void watch() {
        long deadline = Long.MAX_VALUE;
        long nextProbe = System.nanoTime() + PROBE_NANOS;
        boolean overflow = false;
        try {
            while (!closed) {
                WatchKey key = service.poll(50, TimeUnit.MILLISECONDS);
                long now = System.nanoTime();
                if (key != null) {
                    boolean relevant = false;
                    for (var event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            overflow = true;
                            relevant = true;
                        } else if (file.getFileName().equals(event.context())) {
                            relevant = true;
                        }
                    }
                    if (!key.reset()) relevant = true;
                    if (relevant) deadline = now + DEBOUNCE_NANOS;
                }
                if (now >= nextProbe) {
                    if (deadline == Long.MAX_VALUE && !fingerprint().equals(lastFingerprint)) deadline = now;
                    nextProbe = now + PROBE_NANOS;
                }
                if (now >= deadline) {
                    String current = fingerprint();
                    boolean changed = !current.equals(lastFingerprint);
                    lastFingerprint = current;
                    deadline = Long.MAX_VALUE;
                    if (!closed && (changed || overflow)) {
                        try {
                            callback.run();
                        } catch (RuntimeException ignored) {
                            // A client callback failure must not silently disable future refresh.
                        }
                    }
                    overflow = false;
                }
            }
        } catch (ClosedWatchServiceException ignored) {
            // close() owns shutdown.
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            try { service.close(); } catch (IOException ignored) { }
        }
    }

    private String fingerprint() {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile() && attributes.size() <= ProjectFiles.maxSourceBytes()) {
                // Content identity suppresses same-byte touches, including atomic replacement.
                return "content:" + ProjectFiles.fileRevision(file);
            }
            return "unsupported:" + attributes.size() + ":" + attributes.lastModifiedTime() + ":" + attributes.fileKey();
        } catch (IOException exception) {
            return "unreadable:" + exception.getClass().getName() + ":" + exception.getMessage();
        }
    }

    @Override public void close() {
        closed = true;
        try { service.close(); } catch (IOException ignored) { }
        worker.interrupt();
        if (Thread.currentThread() != worker) {
            try {
                worker.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
