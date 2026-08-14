package com.ddd.d3.community.config;

import com.ddd.d3.community.adapter.http.CommunityErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

public final class CommunityRequestSizeFilter extends OncePerRequestFilter {

    public static final int MAX_REQUEST_BYTES = 65_536;
    private static final String POST_PATH = "/v1/community/posts";

    private final ObjectMapper objectMapper;

    public CommunityRequestSizeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = stripMatrixParameters(UriUtils.decode(requestUri.substring(contextPath.length()), StandardCharsets.UTF_8));
        return !("POST".equals(request.getMethod()) && POST_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_REQUEST_BYTES) {
            writeError(response, request);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            writeError(response, request);
            return;
        }
        filterChain.doFilter(new BufferedRequest(request, body), response);
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request) throws IOException {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            correlationId = "unavailable";
        }
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new CommunityErrorResponse(
                        "PAYLOAD_TOO_LARGE",
                        "community request exceeds the 65536-byte limit",
                        correlationId));
    }

    private static String stripMatrixParameters(String path) {
        StringBuilder normalized = new StringBuilder(path.length());
        boolean inMatrixParameter = false;
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character == ';') {
                inMatrixParameter = true;
            } else if (character == '/') {
                inMatrixParameter = false;
                normalized.append(character);
            } else if (!inMatrixParameter) {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("asynchronous reads are not supported");
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
