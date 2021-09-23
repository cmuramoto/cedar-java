package com.nc.cedar;

import java.io.IOException;

import org.junit.Assume;
import org.junit.Test;

public class HugeCedarPerfTests extends BaseHugeCedarTests {

	private static final int STEP = 10_000_000;

	private static final int MAX = 100_000_000;

	@Test
	public void run() throws IOException {
		Assume.assumeTrue("This test requires -XX:MaxDirectMemorySize=4G", Bits.maxDirectMemory() >= 4L * 1024 * 1024 * 1024);

		for (var keys = STEP; keys <= MAX; keys += STEP) {
			var cedar = instantiate();

			fill(cedar, keys, false);

			loop(cedar, keys);

			cedar.close();
		}
	}

}
