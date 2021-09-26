package com.nc.cedar;

import java.io.IOException;

import org.junit.Test;

public class HugeCedarPerfTests extends BaseHugeCedarTests {

	private static final int STEP = 10_000_000;

	private static final int MAX = 100_000_000;

	@Test
	public void run() throws IOException {
		assumeEnoughMemory();

		for (var keys = STEP; keys <= MAX; keys += STEP) {
			var cedar = instantiate();

			fill(cedar, keys, false);

			loop(cedar, keys);

			cedar.close();
		}
	}

}
