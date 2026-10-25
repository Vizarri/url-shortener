package com.rebuy.urlshortener.unit.controller;

import com.rebuy.urlshortener.controller.ShortLinkController;
import com.rebuy.urlshortener.db.model.ShortLink;
import com.rebuy.urlshortener.dto.ShortLinkCreationResult;
import com.rebuy.urlshortener.dto.request.CreateShortLinkRequest;
import com.rebuy.urlshortener.dto.response.CreateShortLinkResponse;
import com.rebuy.urlshortener.service.ShortLinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Tests response construction without a Spring context; MVC binding remains an integration concern. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ShortLinkControllerTest {

    @Mock
    private ShortLinkService shortLinkService;

    @InjectMocks
    private ShortLinkController controller;

    /** Removes the thread-bound mock request even when a test assertion fails. */
    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** Created links use the request's scheme, host, port, and context path, without the API path. */
    @Test
    void createsResponseUsingCurrentApplicationAddress() {
        bindApplicationRequest();
        String targetUrl = "https://example.com/product/42?campaign=rebuy#details";
        ShortLink saved = new ShortLink();
        saved.setCode("aB7x");
        saved.setTargetUrl(targetUrl);
        when(shortLinkService.create(targetUrl)).thenReturn(new ShortLinkCreationResult(saved, true));

        ResponseEntity<CreateShortLinkResponse> response = controller.create(new CreateShortLinkRequest(targetUrl));

        URI expectedUrl = URI.create("https://links.rebuy.test:8443/shortener/aB7x");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isEqualTo(expectedUrl);
        assertThat(response.getBody()).isEqualTo(new CreateShortLinkResponse("aB7x", expectedUrl.toString(), targetUrl));
    }

    /** Reusing a URL returns HTTP 200 with the existing code and the current instance's address. */
    @Test
    void returnsOkForAnExistingLink() {
        bindApplicationRequest();
        String targetUrl = "https://example.com/product/42?campaign=rebuy#details";
        ShortLink existing = new ShortLink();
        existing.setCode("aB7x");
        existing.setTargetUrl(targetUrl);
        when(shortLinkService.create(targetUrl)).thenReturn(new ShortLinkCreationResult(existing, false));

        ResponseEntity<CreateShortLinkResponse> response = controller.create(new CreateShortLinkRequest(targetUrl));

        URI expectedUrl = URI.create("https://links.rebuy.test:8443/shortener/aB7x");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getLocation()).isEqualTo(expectedUrl);
        assertThat(response.getBody()).isEqualTo(new CreateShortLinkResponse("aB7x", expectedUrl.toString(), targetUrl));
    }

    /** Redirect responses retain encoded query values and the destination fragment. */
    @Test
    void redirectsToExactTargetUrl() {
        String targetUrl = "https://example.com/product/42?query=phone%20case&source=rebuy#details";
        when(shortLinkService.getTargetUrl("aB7x")).thenReturn(targetUrl);

        ResponseEntity<Void> response = controller.redirect("aB7x");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(targetUrl));
        assertThat(response.getBody()).isNull();
    }

    /** Binds a nondefault application origin to exercise response URL construction in both success paths. */
    private void bindApplicationRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("links.rebuy.test");
        request.setServerPort(8443);
        request.setContextPath("/shortener");
        request.setRequestURI("/shortener/api/links");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
