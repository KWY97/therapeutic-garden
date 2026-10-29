package com.example.manage.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@ControllerAdvice(assignableTypes = {AdminImageController.class, AdminSiteController.class, AdminSpotController.class})
public class ImageUploadExceptionHandler {
    @ExceptionHandler(MultipartException.class)
    public String uploadError(HttpServletRequest request, MultipartException exception, RedirectAttributes flash) {
        flash.addFlashAttribute("errorMessage", exception instanceof org.springframework.web.multipart.MaxUploadSizeExceededException
                ? "업로드 용량을 초과했습니다. 파일당 10MB 이하, 한 번에 최대 10장을 선택해 주세요."
                : "파일 업로드 요청을 처리하지 못했습니다. 파일을 다시 선택하고 시도해 주세요.");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.matches("/admin/(sites|spots)/[1-9][0-9]*/images"))
            return "redirect:" + path.replaceFirst("/images$", "/edit#place-images");
        if (path.matches("/admin/(sites|spots)/(new|[1-9][0-9]*/edit)"))
            return "redirect:" + path;
        return "redirect:/admin/sites";
    }
}
