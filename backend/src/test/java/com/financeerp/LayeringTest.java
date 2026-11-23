package com.financeerp;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the controller -> service -> repository layering by scanning the source tree:
 * SQL and JdbcTemplate live only in repositories, controllers are thin, and dependencies point one way.
 */
class LayeringTest {
	private static final Path MAIN = Paths.get("src", "main", "java", "com", "financeerp");
	private static final Pattern SQL_LITERAL = Pattern.compile("\"\\s*(SELECT|INSERT INTO|UPDATE|DELETE FROM|WITH)\\s");

	private static List<Path> sources(String layer) throws IOException {
		Path dir = MAIN.resolve(layer);
		if (!Files.isDirectory(dir))
			return List.of();
		try (Stream<Path> s = Files.walk(dir)) {
			return s.filter(p -> p.toString().endsWith(".java")).toList();
		}
	}

	private static String text(Path p) throws IOException {
		return Files.readString(p);
	}

	private static List<String> offenders(String layer, Pattern forbidden) throws IOException {
		List<String> out = new ArrayList<>();
		for (Path p : sources(layer))
			if (forbidden.matcher(text(p)).find())
				out.add(layer + "/" + p.getFileName());
		return out;
	}

	@Test
	void everyLayerExists() throws IOException {
		assertThat(sources("controller")).isNotEmpty();
		assertThat(sources("service")).isNotEmpty();
		assertThat(sources("repository")).isNotEmpty();
	}

	@Test
	void controllersAreThin() throws IOException {
		assertThat(offenders("controller", Pattern.compile("JdbcTemplate|CompanyAccess|com\\.financeerp\\.repository\\.")))
				.as("controllers must delegate to services only").isEmpty();
		assertThat(offenders("controller", SQL_LITERAL)).as("no SQL in controllers").isEmpty();
		for (Path p : sources("controller")) {
			assertThat(p.getFileName().toString()).as("controller naming").endsWith("Controller.java");
			assertThat(text(p)).as(p.getFileName() + " should be a REST controller").contains("@RestController");
		}
	}

	@Test
	void servicesDoNotTouchTheDatabaseDirectly() throws IOException {
		assertThat(offenders("service", Pattern.compile("JdbcTemplate"))).isEmpty();
		assertThat(offenders("service", SQL_LITERAL)).as("SQL belongs in repositories").isEmpty();
		assertThat(offenders("service", Pattern.compile("com\\.financeerp\\.controller\\.|jakarta\\.servlet\\.")))
				.as("services must not depend on the web layer").isEmpty();
	}

	@Test
	void repositoriesOnlyDependDownwards() throws IOException {
		assertThat(offenders("repository", Pattern.compile("com\\.financeerp\\.(controller|service|security)\\.")))
				.isEmpty();
		// Classes that run SQL must be repositories; SQL-constant holders (no JdbcTemplate) are allowed.
		for (Path p : sources("repository"))
			if (text(p).contains("JdbcTemplate"))
				assertThat(text(p)).as(p.getFileName() + " should be a @Repository").contains("@Repository");
	}

	@Test
	void domainIsFreeOfSpringAndOtherLayers() throws IOException {
		assertThat(offenders("domain", Pattern.compile(
				"com\\.financeerp\\.(controller|service|repository|security)\\.|org\\.springframework\\.(jdbc|web|stereotype)")))
				.isEmpty();
	}

	@Test
	void onlyRepositoriesUseJdbcTemplate() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> s = Files.walk(MAIN)) {
			for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
				String rel = MAIN.relativize(p).toString().replace('\\', '/');
				if (!rel.startsWith("repository/") && text(p).contains("JdbcTemplate"))
					offenders.add(rel);
			}
		}
		assertThat(offenders).as("JdbcTemplate is only allowed in com.financeerp.repository").isEmpty();
	}
}
