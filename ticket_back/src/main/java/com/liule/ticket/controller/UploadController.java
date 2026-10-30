package com.liule.ticket.controller;

import cn.hutool.core.util.StrUtil;
import com.liule.ticket.dto.Result;
import com.liule.ticket.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    private static final long MAX_SIZE = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT =
            Set.of("jpg", "jpeg", "png", "webp", "gif");

    /**
     * 上传图片。保留原来的 /upload/blog 路径，返回 name（落盘相对路径）和 url（可直接访问）。
     * <p>
     * 这个接口不再放行，必须登录；图片通过 /uploads/** 公开访问。
     */
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("请选择图片");
        }
        if (image.getSize() > MAX_SIZE) {
            return Result.fail("图片不能超过 5MB");
        }

        String contentType = image.getContentType();
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return Result.fail("图片格式不支持");
        }

        String originalFilename = image.getOriginalFilename();
        if (StrUtil.isBlank(originalFilename)) {
            return Result.fail("文件名为空");
        }
        String suffix = StrUtil.subAfter(originalFilename, ".", true)
                .toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(suffix)) {
            return Result.fail("图片格式不支持，仅限 jpg/jpeg/png/webp/gif");
        }

        String fileName = createNewFileName(suffix);
        Path base = Paths.get(SystemConstants.IMAGE_UPLOAD_DIR)
                .toAbsolutePath()
                .normalize();
        // fileName 以 / 开头，去掉后再拼，避免 Windows 下被当成盘符根路径
        Path target = base.resolve(fileName.substring(1)).normalize();
        if (!target.startsWith(base)) {
            return Result.fail("文件路径非法");
        }

        try {
            Files.createDirectories(target.getParent());
            image.transferTo(target.toFile());
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(Map.of(
                    "name", fileName,
                    "url", "/api/uploads" + fileName));
        } catch (IOException | IllegalStateException e) {
            log.error("文件上传失败：{}", fileName, e);
            return Result.fail("文件上传失败");
        }
    }

    private String createNewFileName(String suffix) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
