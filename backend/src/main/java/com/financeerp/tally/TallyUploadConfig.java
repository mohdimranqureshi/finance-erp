package com.financeerp.tally;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Tally exports can reach 10 MB while Spring's default multipart limit is 1 MB. This keeps every configured
 * spring.servlet.multipart.* setting and only raises the size limits to what the Tally import accepts; other upload
 * endpoints keep enforcing their own, smaller limits in code.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.servlet.multipart", name = "enabled", matchIfMissing = true)
public class TallyUploadConfig {
	static final DataSize MAX_FILE = DataSize.ofMegabytes(11);
	static final DataSize MAX_REQUEST = DataSize.ofMegabytes(12);

	@Bean
	MultipartConfigElement multipartConfigElement(ObjectProvider<MultipartProperties> provider) {
		MultipartProperties props = provider.getIfAvailable(MultipartProperties::new);
		if (props.getMaxFileSize() == null || props.getMaxFileSize().toBytes() >= 0
				&& props.getMaxFileSize().compareTo(MAX_FILE) < 0)
			props.setMaxFileSize(MAX_FILE);
		if (props.getMaxRequestSize() == null || props.getMaxRequestSize().toBytes() >= 0
				&& props.getMaxRequestSize().compareTo(MAX_REQUEST) < 0)
			props.setMaxRequestSize(MAX_REQUEST);
		return props.createMultipartConfig();
	}
}
