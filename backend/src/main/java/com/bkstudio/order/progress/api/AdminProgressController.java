package com.bkstudio.order.progress.api;

import com.bkstudio.order.progress.api.ProgressDtos.Approved;
import com.bkstudio.order.progress.domain.AdminProgressService;
import com.bkstudio.shared.web.ApiException;
import java.security.Principal;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Approve a production stage with optional note and photos. /api/admin/** is ROLE_ADMIN + CSRF (SecurityConfig). */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminProgressController {

    private final AdminProgressService progress;

    public AdminProgressController(AdminProgressService progress) {
        this.progress = progress;
    }

    @PostMapping(path = "/{id}/progress/{stage}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Approved approve(
            @PathVariable long id,
            @PathVariable String stage,
            @RequestParam(required = false) String note,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            Principal principal) {
        List<MultipartFile> parts = files == null ? List.of() : files;
        if (parts.stream().anyMatch(MultipartFile::isEmpty)) {
            throw ApiException.invalidField("files", "files must not contain an empty file.");
        }
        // The client content-type is ignored: ImageService sniffs the real format from the bytes.
        return progress.approve(
                id,
                Long.parseLong(principal.getName()),
                stage,
                note,
                parts.stream()
                        .map(f -> (AdminProgressService.Upload) f::getInputStream)
                        .toList());
    }
}
