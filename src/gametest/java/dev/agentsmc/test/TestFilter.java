package dev.agentsmc.test;

/** AGENTSMC_ONLY=&lt;name&gt; runs just one of the client game tests (body, screenshot, menu). */
final class TestFilter {
	private TestFilter() {
	}

	static boolean skip(String name) {
		String only = System.getenv("AGENTSMC_ONLY");
		return only != null && !only.isBlank() && !only.trim().equalsIgnoreCase(name);
	}
}
