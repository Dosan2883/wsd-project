package com.gamenews.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    public record ErrorBody(String code, String message, Map<String, String> fields,
                            @JsonInclude(JsonInclude.Include.NON_NULL) AccountSuspendedException.Suspension suspension) {
        public ErrorBody(String code, String message, Map<String, String> fields) { this(code, message, fields, null); }
    }

    @ExceptionHandler(AccountSuspendedException.class)
    ResponseEntity<ErrorBody> suspended(AccountSuspendedException error) {
        return ResponseEntity.status(403).body(suspensionBody(error));
    }

    static ErrorBody suspensionBody(AccountSuspendedException error) {
        return new ErrorBody("ACCOUNT_SUSPENDED", "정지된 계정입니다.", Map.of(), error.getSuspension());
    }

    static void writeSuspension(ObjectMapper mapper, HttpServletResponse response, AccountSuspendedException error) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), suspensionBody(error));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorBody(e.code(), e.getMessage(), Map.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e) {
        var fields = new LinkedHashMap<String, String>();
        e.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR", "입력 내용을 확인해 주세요.", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> malformed() {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", "허용되지 않는 필드 또는 잘못된 요청 형식입니다.", Map.of()));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> invalidParameter() {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_PARAMETER", "페이지·개수·게임·검색어·글 번호를 확인해 주세요.", Map.of()));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    ResponseEntity<ErrorBody> conflict() {
        return ResponseEntity.status(409).body(new ErrorBody("STATE_CONFLICT", "다른 요청이 먼저 상태를 변경했습니다. 새로고침 후 다시 확인해 주세요.", Map.of()));
    }

    static void write(ObjectMapper mapper, HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), new ErrorBody(code, message, Map.of()));
    }
}
