package com.dev.semsearch.search.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Web MVC configuration for serving the React SPA frontend.
 *
 * <p>In production, the Vite-built React app is placed in
 * {@code src/main/resources/static/}. This configuration ensures that:
 * <ol>
 *   <li>Static files (JS, CSS, images) are served directly.</li>
 *   <li>All non-API, non-file routes fall back to {@code index.html}
 *       so React Router can handle client-side routing.</li>
 * </ol>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve static assets from the classpath (Vite build output)
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // Unknown API and actuator paths must 404, not quietly return the
                        // SPA's index.html with a 200 (which hides typos and broken clients).
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                            return null;
                        }
                        Resource requested = location.createRelative(resourcePath);
                        // If the file exists, serve it directly (JS, CSS, images, etc.)
                        // Otherwise, fall back to index.html for React Router
                        return requested.exists() && requested.isReadable()
                                ? requested
                                : new ClassPathResource("/static/index.html");
                    }
                });
    }
}
