package com.vvttai.smart_link_shortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableJpaAuditing
public class SmartLinkShortenerApplication {
	public static void main(String[] args) {
		loadDotenv();
		SpringApplication.run(SmartLinkShortenerApplication.class, args);
	}

	/**
	 * Load file .env thành System properties.
	 * Biến môi trường hệ thống (OS env) sẽ được ưu tiên hơn.
	 */
	private static void loadDotenv() {
		Path envPath = Paths.get(".env");
		if (!Files.exists(envPath)) {
			System.out.println("[ENV] Không tìm thấy file .env - sử dụng biến môi trường hệ thống");
			return;
		}

		try {
			int count = 0;
			for (String line : Files.readAllLines(envPath)) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;

				int eq = line.indexOf('=');
				if (eq <= 0) continue;

				String key = line.substring(0, eq).trim();
				String value = line.substring(eq + 1).trim();

				// Xóa dấu ngoặc kép
				if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
					value = value.substring(1, value.length() - 1);
				}

				// Chỉ set nếu chưa có trong env hệ thống
				if (System.getenv(key) == null && System.getProperty(key) == null) {
					System.setProperty(key, value);
					count++;
				}
			}
			System.out.println("[ENV] ✅ Đã load " + count + " biến từ file .env");
		} catch (IOException e) {
			System.err.println("[ENV] ⚠️ Không thể đọc file .env: " + e.getMessage());
		}
	}
}
