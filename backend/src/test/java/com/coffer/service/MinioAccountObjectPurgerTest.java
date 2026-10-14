package com.coffer.service;

import com.coffer.config.MinioConfig;
import io.minio.*;
import io.minio.messages.Item;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinioAccountObjectPurgerTest {
    MinioClient client;
    JdbcTemplate jdbc;
    MinioAccountObjectPurger purger;
    @BeforeEach void setup() {
        client=mock(MinioClient.class); jdbc=mock(JdbcTemplate.class);
        var properties=new MinioConfig.MinioProperties(); properties.setBucketName("isolated-fixture");
        purger=new MinioAccountObjectPurger(client,properties,jdbc);
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(7L))).thenReturn(1L);
    }
    Result<Item> version(String key,String version) {
        Item item=mock(Item.class); when(item.objectName()).thenReturn(key); when(item.versionId()).thenReturn(version); return new Result<>(item);
    }
    @Test void removesEveryHistoricalVersionInOnlyTheAuthorizedOwnerPrefix() throws Exception {
        var versions=List.of(version("users/7/objects/private.pdf","v1"),version("users/7/objects/private.pdf","v2"),version("users/7/objects/private.pdf","delete-marker"));
        when(client.listObjects(any())).thenReturn(versions)
                .thenReturn(List.of());
        purger.purge(7L);
        var listing=ArgumentCaptor.forClass(ListObjectsArgs.class); verify(client,times(2)).listObjects(listing.capture());
        assertThat(listing.getValue().prefix()).isEqualTo("users/7/"); assertThat(listing.getValue().includeVersions()).isTrue();
        var removals=ArgumentCaptor.forClass(RemoveObjectArgs.class); verify(client,times(3)).removeObject(removals.capture());
        assertThat(removals.getAllValues()).extracting(RemoveObjectArgs::versionId).containsExactly("v1","v2","delete-marker");
        assertThat(removals.getAllValues()).extracting(RemoveObjectArgs::object).containsOnly("users/7/objects/private.pdf");
        verify(client,never()).getObject(any());
    }
    @Test void foreignOwnerResultIsNeverDeletedOrExposed() throws Exception {
        var versions=List.of(version("users/70/foreign-private.pdf","foreign"));
        when(client.listObjects(any())).thenReturn(versions);
        assertThatThrownBy(() -> purger.purge(7L)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("foreign-private").hasNoCause();
        verify(client,never()).removeObject(any()); verify(client,never()).getObject(any());
    }
    @Test void absentDeletionAuthorizationPerformsNoStorageIo() {
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(7L))).thenReturn(0L);
        assertThatThrownBy(() -> purger.purge(7L)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(client);
    }
}
