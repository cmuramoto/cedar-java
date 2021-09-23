package com.nc.cedar;

import static org.junit.Assume.assumeFalse;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.junit.FixMethodOrder;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(Parameterized.class)
public abstract class BaseCedarTests {

	static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	static void log(Object msg) {
		System.out.printf("[%s][%s]\n", LocalTime.now().format(DTF), msg);
	}

	static void log(String fmt, Object... args) {
		System.out.printf("[%s][%s]\n", LocalTime.now().format(DTF), String.format(fmt, args));
	}

	@Parameters(name = "reduced={0}")
	public static Collection<Object[]> parameters() {
		var first = (System.nanoTime() & 1) == 0;

		return List.of(new Object[]{ first }, new Object[]{ !first });
	}

	static long toMillis(long nanos) {
		return TimeUnit.NANOSECONDS.toMillis(nanos);
	}

	static long toMillis(LongAdder adder) {
		return toMillis(adder.sum());
	}

	@Parameter(0)
	public boolean reduced;

	BaseCedar deserialize(Path tmp, boolean copy) {
		return reduced ? ReducedCedar.deserialize(tmp, copy) : Cedar.deserialize(tmp, copy);
	}

	BaseCedar instantiate() {
		return reduced ? new ReducedCedar() : new Cedar();
	}

	void unsupportedInReduced() {
		assumeFalse("Reduced only works with ascii", reduced);
	}
}
