package com.nc.cedar;

import java.nio.charset.Charset;

import jdk.internal.misc.Unsafe;

public final class Bits {

	static final Charset UTF8 = Charset.forName("UTF-8");
	static final Unsafe U;
	static final long C_OFF;
	static final long V_OFF;
	static final byte[] EMPTY;

	static {
		try {
			var f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			U = (Unsafe) f.get(null);
			C_OFF = U.objectFieldOffset(String.class, "coder");
			V_OFF = U.objectFieldOffset(String.class, "value");
			EMPTY = unwrap("");
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	static byte coder(String s) {
		return U.getByte(s, C_OFF);
	}

	private static byte[] unwrap(String s) {
		return (byte[]) U.getReference(s, V_OFF);
	}

	static byte[] utf8(String s) {
		byte[] rv;
		if (s == null || s.isEmpty()) {
			rv = EMPTY;
		} else if (coder(s) == 0) {
			rv = unwrap(s);
		} else {
			rv = s.getBytes(UTF8);
		}
		return rv;
	}

}
