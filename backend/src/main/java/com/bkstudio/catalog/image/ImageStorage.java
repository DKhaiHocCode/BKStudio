package com.bkstudio.catalog.image;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Thin S3 wrapper: puts and deletes WebP renditions under {@code {storageKey}/{width}.webp}. */
@Component
class ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(ImageStorage.class);

    private final ObjectProvider<S3Client> client;
    private final StorageProperties props;

    ImageStorage(ObjectProvider<S3Client> client, StorageProperties props) {
        this.client = client;
        this.props = props;
    }

    static String objectKey(String storageKey, int width) {
        return storageKey + "/" + width + ".webp";
    }

    void put(String storageKey, int width, byte[] webp) {
        client.getObject()
                .putObject(
                        PutObjectRequest.builder()
                                .bucket(props.bucket())
                                .key(objectKey(storageKey, width))
                                .contentType("image/webp")
                                .cacheControl("public, max-age=31536000, immutable")
                                .build(),
                        RequestBody.fromBytes(webp));
    }

    void delete(String storageKey, List<Integer> widths) {
        List<ObjectIdentifier> ids = widths.stream()
                .map(w ->
                        ObjectIdentifier.builder().key(objectKey(storageKey, w)).build())
                .toList();
        var response = client.getObject()
                .deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(props.bucket())
                        .delete(Delete.builder().objects(ids).build())
                        .build());
        if (response.hasErrors() && !response.errors().isEmpty()) {
            log.warn(
                    "Failed to delete objects: {}",
                    response.errors().stream()
                            .map(e -> e.key() + " (" + e.code() + ")")
                            .toList());
        }
    }
}
