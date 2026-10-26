package com.bkstudio.catalog.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageServiceTest {

    private final ImageStorage storage = mock(ImageStorage.class);
    private final ImageService service = new ImageService(new ImageProcessor(), storage);

    @Test
    void store_whenSecondPutFails_deletesAllWidthsAndThrowsInternalError() {
        doNothing().doThrow(new RuntimeException("boom")).when(storage).put(anyString(), anyInt(), any());

        assertThatThrownBy(() -> service.store(new ByteArrayInputStream(TestImages.jpeg(3000, 2000))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        verify(storage).delete(anyString(), eq(List.of(400, 1000, 2000)));
    }

    @Test
    void delete_rejectsMalformedKeysAndDeletesNothing() {
        for (String bad :
                new String[] {"other/dir", "", null, "products/../x", "products/ABCDEF01-0000-0000-0000-000000000000"
                }) {
            assertThatThrownBy(() -> service.delete(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(storage);
    }

    @Test
    void delete_validKey_isForwarded() {
        String key = "products/00000000-0000-0000-0000-000000000000";
        service.delete(key);
        verify(storage).delete(key, List.of(400, 1000, 2000));
        assertThat(key).startsWith("products/");
    }
}
