package com.opensmpsdeck.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ProjectPersistenceTest {
    @TempDir Path directory;

    private static StudioProject project(String name) {
        return new StudioProject(1, name, "S3K", 24, 120, List.of(), List.of(), List.of());
    }

    private Path create(String name) throws IOException {
        Path path = directory.resolve(name);
        ProjectFiles.save(path, project("Original"), null);
        return path;
    }

    @Test void revisionHashesExactBytesRatherThanParsedContent() throws Exception {
        Path file = create("song.json");
        byte[] bytes = Files.readAllBytes(file);
        var first = ProjectFiles.load(file);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), first.revision());
        Files.writeString(file, new String(bytes, StandardCharsets.UTF_8) + "\n");
        var second = ProjectFiles.load(file);
        assertEquals(first.project(), second.project());
        assertNotEquals(first.revision(), second.revision());
    }

    @Test void savingReplacesRealFileWithoutLeavingStagingFiles() throws Exception {
        Path file = create("song.json");
        var original = ProjectFiles.load(file);
        var saved = ProjectFiles.save(file, project("Saved"), original.revision());
        assertEquals(project("Saved"), ProjectFiles.load(file).project());
        assertEquals(saved, ProjectFiles.load(file));
        try (var files = Files.list(directory)) { assertEquals(List.of(file), files.toList()); }
    }

    @Test void staleSaveAndCreateNewNeverOverwriteAnotherVersion() throws Exception {
        Path file = create("song.json");
        var original = ProjectFiles.load(file);
        Files.writeString(file, ProjectFormat.write(project("External")));
        byte[] external = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> ProjectFiles.save(file, project("Local"), original.revision()));
        assertArrayEquals(external, Files.readAllBytes(file));
        assertThrows(IOException.class, () -> ProjectFiles.save(file, project("Local"), null));
        assertArrayEquals(external, Files.readAllBytes(file));
        assertThrows(IOException.class, () -> ProjectFiles.save(directory.resolve("missing.json"), project("Local"), original.revision()));
        assertFalse(Files.exists(directory.resolve("missing.json")));
    }

    @Test void competingCreateNewSavesHaveOneWinnerAndLeaveNoStagingFiles() throws Exception {
        Path file = directory.resolve("race.json");
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int index = 0; index < 12; index++) {
            String name = "Writer " + index;
            tasks.add(() -> {
                try {
                    ProjectFiles.save(file, project(name), null);
                    return true;
                } catch (java.nio.file.FileAlreadyExistsException expected) {
                    return false;
                }
            });
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            long winners = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) winners++;
            assertEquals(1, winners);
        }
        assertTrue(ProjectFiles.load(file).project().name().startsWith("Writer "));
        try (var files = Files.list(directory)) { assertEquals(List.of(file), files.toList()); }
    }

    @Test void savingThroughSymbolicLinkCannotReplaceLinkOrTarget() throws Exception {
        Path target = create("target.json");
        Path link = directory.resolve("link.json");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException exception) {
            org.junit.jupiter.api.Assumptions.abort("Filesystem does not support symbolic links");
        }
        String revision = ProjectFiles.load(target).revision();
        assertThrows(IOException.class, () -> ProjectFiles.save(link, project("Local"), revision));
        assertThrows(IOException.class, () -> ProjectFiles.save(link, project("Local"), null));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(project("Original"), ProjectFiles.load(target).project());
    }

    @Test void boundedAndMalformedInputCannotLoad() throws Exception {
        Path file = directory.resolve("song.json");
        Files.write(file, new byte[ProjectFiles.MAX_FILE_BYTES + 1]);
        assertThrows(IOException.class, () -> ProjectFiles.load(file));
        Files.write(file, new byte[] {(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> ProjectFiles.load(file));
        Files.writeString(file, "{\"formatVersion\":1}");
        assertThrows(IOException.class, () -> ProjectFiles.load(file));
    }

    @Test void cleanDocumentAdoptsAtomicReplacementAndIgnoresIdenticalBytes() throws Exception {
        Path file = create("song.json");
        var document = ProjectDocument.open(file);
        Path replacement = directory.resolve("replacement.json");
        Files.writeString(replacement, ProjectFormat.write(project("External")));
        Files.move(replacement, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(document.refresh());
        assertEquals(project("External"), document.project());
        assertFalse(document.dirty());
        assertFalse(document.conflicted());
        Files.write(file, Files.readAllBytes(file));
        assertFalse(document.refresh());
    }

    @Test void incompleteThenValidInputRetainsAndRecoversLastValidProject() throws Exception {
        Path file = create("song.json");
        var document = ProjectDocument.open(file);
        Files.writeString(file, "{");
        assertFalse(document.refresh());
        assertEquals(project("Original"), document.project());
        assertFalse(document.message().isBlank());
        Files.writeString(file, ProjectFormat.write(project("Recovered")));
        assertTrue(document.refresh());
        assertEquals(project("Recovered"), document.project());
        assertFalse(document.conflicted());
    }

    @Test void deletionAndRecreationRetainsAndThenRefreshes() throws Exception {
        Path file = create("song.json");
        var document = ProjectDocument.open(file);
        Files.delete(file);
        assertFalse(document.refresh());
        assertEquals(project("Original"), document.project());
        assertFalse(document.message().isBlank());
        Files.writeString(file, ProjectFormat.write(project("Recreated")));
        assertTrue(document.refresh());
        assertEquals(project("Recreated"), document.project());
    }

    @Test void dirtyConflictBlocksSaveAndReloadPreservesUniqueLocalRecovery() throws Exception {
        Path file = create("song.json");
        var document = ProjectDocument.open(file);
        document.edit(project("Local"));
        Files.writeString(file, ProjectFormat.write(project("External")));
        assertFalse(document.refresh());
        assertTrue(document.dirty());
        assertTrue(document.conflicted());
        assertEquals(project("Local"), document.project());
        assertThrows(IOException.class, document::save);
        assertEquals(project("External"), ProjectFiles.load(file).project());
        Path recovery = document.reloadKeepingRecovery();
        assertNotNull(recovery);
        assertNotEquals(file, recovery);
        assertEquals(project("Local"), ProjectFiles.load(recovery).project());
        assertEquals(project("External"), document.project());
        assertFalse(document.dirty());
        assertFalse(document.conflicted());
        document.edit(project("Second local"));
        Path another = document.reloadKeepingRecovery();
        assertNotEquals(recovery, another);
        assertEquals(project("Local"), ProjectFiles.load(recovery).project());
        assertEquals(project("Second local"), ProjectFiles.load(another).project());
    }

    @Test void invalidExternalReloadPreservesDirtyWorkAndCreatesNoRecovery() throws Exception {
        Path file = create("song.json");
        var document = ProjectDocument.open(file);
        document.edit(project("Local"));
        Files.writeString(file, "{");
        assertFalse(document.refresh());
        assertTrue(document.conflicted());
        assertThrows(IOException.class, document::reloadKeepingRecovery);
        assertEquals(project("Local"), document.project());
        assertTrue(document.dirty());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void saveWithoutPriorRefreshStillRefusesExternalChanges() throws Exception {
        Path file = create("song.json");
        var document = ProjectDocument.open(file);
        document.edit(project("Local"));
        Files.writeString(file, ProjectFormat.write(project("External")));
        assertThrows(IOException.class, document::save);
        assertTrue(document.conflicted());
        assertEquals(project("Local"), document.project());
        assertEquals(project("External"), ProjectFiles.load(file).project());
    }

    @Test void ownSaveHasNoRefreshEchoAndUndoReturnsToClean() throws Exception {
        Path file = create("song.json");
        var document = ProjectDocument.open(file);
        document.edit(project("Local"));
        assertTrue(document.dirty());
        document.edit(project("Original"));
        assertFalse(document.dirty());
        document.edit(project("Saved"));
        document.save();
        assertFalse(document.dirty());
        assertFalse(document.refresh());
        assertFalse(document.conflicted());
        assertEquals(project("Saved"), document.project());
    }

    @Test void transientConflictClearsWhenExactBaselineReturns() throws Exception {
        Path file = create("song.json");
        byte[] baseline = Files.readAllBytes(file);
        var document = ProjectDocument.open(file);
        document.edit(project("Local"));
        Files.writeString(file, "{");
        document.refresh();
        assertTrue(document.conflicted());
        Files.write(file, baseline);
        assertFalse(document.refresh());
        assertFalse(document.conflicted());
        assertTrue(document.dirty());
        document.save();
        assertEquals(project("Local"), ProjectFiles.load(file).project());
    }
}
