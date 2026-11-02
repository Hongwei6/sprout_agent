package com.sprout.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostBashToolTest {
    @TempDir Path directory;

    @Test
    void runsGeneratedScriptOnHostInRequestedDirectory() throws Exception {
        Path script = directory.resolve("generated.sh");
        Files.writeString(script, "printf 'from host script' > result.txt\n");
        HostBashTool tool = new HostBashTool();
        assertEquals("Exit code 0:\n", tool.bash("bash generated.sh", directory.toString()));
        assertEquals("from host script", Files.readString(directory.resolve("result.txt")));
        assertEquals("Exit code 7:\n", tool.bash("exit 7", directory.toString()));
        assertThrows(IllegalArgumentException.class, () -> tool.bash("pwd", "relative"));
        assertThrows(IllegalStateException.class,
                () -> tool.bash("printf '%100001s' x", directory.toString()));
    }
}
