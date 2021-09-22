package com.nc.cedar;

import static org.junit.Assume.assumeFalse;

import java.util.Collection;
import java.util.List;

import org.junit.FixMethodOrder;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(Parameterized.class)
public abstract class BaseCedarTests {

	@Parameters(name = "reduced={0}")
	public static Collection<Object[]> parameters() {
		return List.of(new Object[]{ false }, new Object[]{ true });
	}

	@Parameter(0)
	public boolean reduced;

	BaseCedar instantiate() {
		return reduced ? new ReducedCedar() : new Cedar();
	}

	void unsupportedInReduced() {
		assumeFalse("Reduced only works with ascii", reduced);
	}

}
