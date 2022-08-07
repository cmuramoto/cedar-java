package com.nc.cedar;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.nio.charset.Charset;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import static jdk.incubator.foreign.ValueLayout.JAVA_BYTE;


import jdk.incubator.foreign.MemorySegment;
import jdk.internal.misc.Unsafe;

public final class Bits {

	static final Charset UTF8 = Charset.forName("UTF-8");
	static final Unsafe U;
	static final long C_OFF;
	static final long V_OFF;
	static final long M_OFF;

	static final byte[] EMPTY;
	static final MethodHandle NO_COPY;

	static {
		try {
			var f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			U = (Unsafe) f.get(null);
			C_OFF = U.objectFieldOffset(String.class, "coder");
			V_OFF = U.objectFieldOffset(String.class, "value");
			M_OFF = U.objectFieldOffset(Class.forName("jdk.internal.foreign.NativeMemorySegmentImpl"), "min");
			EMPTY = unwrap("");
			NO_COPY = trusted().findConstructor(String.class, MethodType.methodType(void.class, byte[].class, byte.class));
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	static byte coder(String s) {
		return U.getByte(s, C_OFF);
	}

	static int i32(long v) {
		return (int) v;
	}

	public static long min(MemorySegment buffer) {
		return U.getLong(buffer, M_OFF);
	}
	
	public static long maxDirectMemory() {
		return jdk.internal.misc.VM.maxDirectMemory();
	}

	public static String newAscii(byte[] chunk) {
		return newString(chunk, (byte) 0);
	}

	public static String newString(byte[] chunk, byte coder) {
		try {
			return (String) NO_COPY.invoke(chunk, coder);
		} catch (Throwable e) {
			throw new InternalError(e);
		}
	}

	static long reallocateMemory(long address, long newSize) {
		return U.reallocateMemory(address, newSize);
	}

	public static LongStream split(MemorySegment contiguous, byte sep) {
		var itr = new PrimitiveIterator.OfLong() {
			long pos;
			boolean ready;

			private void advance() {
				var p = pos;
				var c = contiguous;
				var s = sep;

				if (p < c.byteSize()) {
					do {
						if (c.get(JAVA_BYTE, p) == s) {
							break;
						}
						p++;
					} while (p < c.byteSize());
					pos = p;
					ready = true;
				} else {
					pos = -1;
					return;
				}
			}

			@Override
			public boolean hasNext() {
				if (!ready) {
					advance();
				}
				return ready;
			}

			@Override
			public long nextLong() {
				var p = pos;

				if (p < 0 || (ready = !ready)) {
					throw new IllegalStateException();
				}

				pos++;

				return p;
			}
		};

		return StreamSupport.longStream(Spliterators.spliteratorUnknownSize(itr, Spliterator.ORDERED), false);
	}

	public static LongStream split(MemorySegment contiguous, char sep) {
		return split(contiguous, (byte) sep);
	}

	public static Stream<String> stream(MemorySegment contiguous, byte sep) {
		var offsets = split(contiguous, sep);
		var ptr = new Ptr();

		return offsets.mapToObj(curr -> {
			var prev = ptr.v;
			var len = curr - prev;

			if (len == 0) {
				return "";
			}

			if (len > 0 && len <= Integer.MAX_VALUE) {
				var chunk = contiguous.asSlice(prev, len).toArray(JAVA_BYTE);
				ptr.v = curr + 1;

				return new String(chunk, UTF8);
			}

			throw new IllegalStateException("Invalid length: " + len);
		});
	}

	public static Stream<String> stream(MemorySegment contiguous, char sep) {
		return stream(contiguous, (byte) sep);
	}

	private static Lookup trusted() {
		try {
			var f = java.lang.invoke.MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");

			// Unsupported in native image (use reflection)
			return (Lookup) U.getReference(U.staticFieldBase(f), U.staticFieldOffset(f));
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	static int u32(byte v) {
		return v & 0xFF;
	}

	static long u64(int n) {
		// this is the right conversion, however the code itself it not ready to deal with negative
		// values, so keep the upcast as a signed promotion.
		// return n & 0XFFFFFFFFL;
		return n;
	}

	static byte u8(long n) {
		return (byte) (n & 0xFF);
	}

	static byte[] unwrap(String s) {
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
