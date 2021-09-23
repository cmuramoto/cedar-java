package com.nc.cedar;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.Assume;
import org.junit.Test;

public class HugeCedarTests extends BaseHugeCedarTests {

	private static final int MAX = 127_153_514;

	@Test
	public void run() throws IOException {
		Assume.assumeTrue("This test requires -XX:MaxDirectMemorySize=4G", Bits.maxDirectMemory() >= 4L * 1024 * 1024 * 1024);

		var cedar = instantiate();

		fill(cedar, MAX);

		log("Starting lookups (Original Image)");
		loop(cedar, MAX);

		log("Serializing");
		var tmp = Files.createTempFile("cedar", "bin");
		cedar.serialize(tmp);
		cedar.close();

		log("Deserializing");

		cedar = deserialize(tmp, false);

		log("Starting queries (Deserialized Image)");
		loop(cedar, MAX);
		cedar.close();
	}
}
