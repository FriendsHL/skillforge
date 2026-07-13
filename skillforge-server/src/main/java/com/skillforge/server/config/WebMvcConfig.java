package com.skillforge.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final com.skillforge.server.mobile.MobileAuthInterceptor mobileAuthInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor,
                        com.skillforge.server.mobile.MobileAuthInterceptor mobileAuthInterceptor) {
        this.authInterceptor = authInterceptor;
        this.mobileAuthInterceptor = mobileAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**")
                .excludePathPatterns("/api/channels/*/webhook")
                .excludePathPatterns("/api/mobile/pairings/*/claim")
                .excludePathPatterns("/api/mobile/client/**");

        registry.addInterceptor(mobileAuthInterceptor)
                .addPathPatterns("/api/mobile/client/**");
    }
}
