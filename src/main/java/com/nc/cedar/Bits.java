package com.nc.cedar;

import java.nio.charset.Charset;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemorySegment;
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

	static int i32(long v) {
		return (int) v;
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
						if (MemoryAccess.getByteAtOffset(c, p) == s) {
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
				var chunk = contiguous.asSlice(prev, len).toByteArray();
				ptr.v = curr + 1;

				return new String(chunk, UTF8);
			}

			throw new IllegalStateException("Invalid length: " + len);
		});
	}

	public static Stream<String> stream(MemorySegment contiguous, char sep) {
		return stream(contiguous, (byte) sep);
	}

	static int u32(byte v) {
		return v & 0xFF;
	}

	static long u64(int n) {
		return n & 0XFFFFFFFFL;
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
