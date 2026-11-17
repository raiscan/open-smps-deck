package com.opensmpsdeck.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ProjectWatcherTest {
    @TempDir Path directory;

    @Test void observesAtomicReplacementOffCallingThreadAndCloseReleasesWorker() throws Exception {
        Path file = directory.resolve("watched.json");
        Files.writeString(file, "initial");
        var callback = new CountDownLatch(1);
        var callbackThread = new AtomicReference<Thread>();
        Set<Long> before = Thread.getAllStackTraces().keySet().stream().map(Thread::threadId).collect(Collectors.toSet());
        ProjectWatcher watcher = new ProjectWatcher(file, () -> {
            callbackThread.set(Thread.currentThread());
            callback.countDown();
        });
        try {
            Path replacement = directory.resolve("next.json");
            Files.writeString(replacement, "replacement");
            Files.move(replacement, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            assertTrue(callback.await(5, TimeUnit.SECONDS), "atomic replacement callback");
            assertNotEquals(Thread.currentThread(), callbackThread.get());
        } finally { watcher.close(); }
        assertFalse(callbackThread.get().isAlive(), "close joins its worker");
        assertTimeout(Duration.ofSeconds(1), watcher::close);
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !before.contains(thread.threadId()))
                .noneMatch(thread -> thread.getName().startsWith("opensmps-project-watch-")));
    }

    @Test void observesDeletionAndRecreationWithoutReopeningWatcher() throws Exception {
        Path file = directory.resolve("watched.json");
        Files.writeString(file, "initial");
        var observed = new AtomicReference<>(new CountDownLatch(1));
        try (var watcher = new ProjectWatcher(file, () -> observed.get().countDown())) {
            Files.delete(file);
            assertTrue(observed.get().await(5, TimeUnit.SECONDS), "deletion callback");
            observed.set(new CountDownLatch(1));
            Files.writeString(file, "recreated");
            assertTrue(observed.get().await(5, TimeUnit.SECONDS), "recreation callback");
        }
    }

    @Test void unrelatedFilesAndIdenticalContentDoNotTriggerRefreshCallback() throws Exception {
        Path file = directory.resolve("watched.json");
        Files.writeString(file, "initial");
        var callback = new CountDownLatch(1);
        try (var watcher = new ProjectWatcher(file, callback::countDown)) {
            Files.writeString(directory.resolve("other.json"), "other");
            Files.writeString(file, "initial");
            assertFalse(callback.await(700, TimeUnit.MILLISECONDS));
            Files.writeString(file, "changed");
            assertTrue(callback.await(5, TimeUnit.SECONDS));
        }
    }
}
