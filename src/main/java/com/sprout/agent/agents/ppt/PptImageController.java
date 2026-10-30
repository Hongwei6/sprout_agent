package com.sprout.agent.agents.ppt;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import io.minio.errors.ErrorResponseException;

@RestController
@RequestMapping("/api/ppt/images")
public class PptImageController {
    private final PptImageService images;

    public PptImageController(PptImageService images) {
        this.images = images;
    }

    @GetMapping("/{id:[0-9a-f]{32}}")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        try {
            PptImageService.StoredImage image = images.readImage(id);
            return ResponseEntity.ok().contentType(image.contentType())
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                    .body(image.bytes());
        } catch (IllegalStateException ex) {
            if (ex.getCause() instanceof ErrorResponseException missing
                    && ("NoSuchKey".equals(missing.errorResponse().code())
                        || "NoSuchObject".equals(missing.errorResponse().code()))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 图片不存在", ex);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法读取 PPT 图片", ex);
        }
    }
}
