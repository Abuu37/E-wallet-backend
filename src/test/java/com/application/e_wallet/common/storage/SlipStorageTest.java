package com.application.e_wallet.common.storage;

import com.application.e_wallet.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlipStorageTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    @TempDir Path dir;

    @Test
    void storesPhotosAndPdfsUnderARandomName() {
        SlipStorage storage = new SlipStorage(dir.toString());
        for (byte[] file : new byte[][]{JPEG, PNG, PDF}) {
            SlipStorage.Stored stored = storage.save(file);
            assertThat(stored.key()).matches("[0-9a-f]{32}\\.(jpg|png|pdf)");
            assertThat(storage.load(stored.key(), stored.contentType()).bytes()).isEqualTo(file);
        }
    }

    @Test
    void theTypeComesFromTheBytesNotTheName() {
        SlipStorage storage = new SlipStorage(dir.toString());
        assertThatThrownBy(() -> storage.save("<script>alert(1)</script>".getBytes())).isInstanceOf(BadRequestException.class).hasMessageContaining("JPG or PNG");
        assertThatThrownBy(() -> storage.save("MZ-an-exe".getBytes())).isInstanceOf(BadRequestException.class);
    }

    @Test
    void emptyAndOversizedFilesAreRefused() {
        SlipStorage storage = new SlipStorage(dir.toString());
        assertThatThrownBy(() -> storage.save(new byte[0])).isInstanceOf(BadRequestException.class);
        byte[] big = new byte[(int) SlipStorage.MAX_BYTES + 1];
        System.arraycopy(JPEG, 0, big, 0, JPEG.length);
        assertThatThrownBy(() -> storage.save(big)).isInstanceOf(BadRequestException.class).hasMessageContaining("too large");
    }

    @Test
    void aKeyThatIsNotOursCannotReadOtherFiles() {
        SlipStorage storage = new SlipStorage(dir.toString());
        assertThatThrownBy(() -> storage.load("../../etc/passwd", "image/png")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> storage.load(null, "image/png")).isInstanceOf(BadRequestException.class);
    }
}
