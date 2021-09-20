package com.nc.cedar;

import jdk.incubator.foreign.MemorySegment;

/**
 * Base storage for cedar. Abstract methods are non-performance critical.
 *
 * @author cmuramoto
 */
abstract class CedarBuffer {

	static final boolean BOUNDS_CHECK = false;

	static final long toOffset(long ix, long unit) {
		return ix * unit;
	}

	MemorySegment buffer;

	long pos;

	CedarBuffer() {
	}

	/**
	 * Subclasses should hoist a constant unit field so safeOffset can be inlined
	 *
	 * @param cap
	 * @param unit
	 */
	CedarBuffer(long cap, long unit) {
		this.buffer = MemorySegment.allocateNative(cap * unit, alignment()).share();
	}

	abstract long alignment();

	final long byteSize() {
		return buffer.byteSize();
	}

	/**
	 * Buffer capacity is derived from it's unit. Capacity is not called often, so there's no need
	 * to hoist it in a field.
	 *
	 * @param unit
	 * @return
	 */
	final long cap(long unit) {
		return byteSize() / unit;
	}

	final void close() {
		var b = buffer;
		if (b != null && b.isAlive()) {
			b.close();
		}
	}

	final void fill(int b) {
		buffer.fill((byte) b);
	}

	final void grow(long more, long unit) {
		var newLen = more * unit + buffer.byteSize();

		var next = MemorySegment.allocateNative(newLen, alignment()).share();

		var curr = this.buffer;

		next.copyFrom(curr);

		if (!curr.isMapped()) {
			this.buffer.close();
		}

		this.buffer = next;
	}

	final boolean isMapped() {
		var b = buffer;
		return b != null && b.isMapped();
	}

	final void jump(long unit) {
		pos = cap(unit);
	}

	final void resize(long newSize, long unit) {
		var newLen = newSize * unit;
		var next = MemorySegment.allocateNative(newLen, alignment()).share();
		var curr = this.buffer;

		if (curr.byteSize() > next.byteSize()) {
			next.copyFrom(curr.asSlice(0, next.byteSize()));
		} else {
			next.copyFrom(curr);
		}
		if (!curr.isMapped()) {
			this.buffer.close();
		}

		var ix = this.pos;
		this.pos = newSize;

		var off = toOffset(ix, unit);
		for (; ix < newSize; ix++) {
			set(next, off);
			off += unit;
		}

		this.buffer = next;
	}

	@SuppressWarnings("unused")
	final long safeOffset(long ix, long unit) {
		var off = toOffset(ix, unit);
		if (BOUNDS_CHECK && (ix < 0 || ix >= this.pos || (off + unit) > byteSize())) {
			throw new Error("Out of bounds " + ix);
		}
		return off;
	}

	abstract void set(MemorySegment buffer, long offset);

	final long totalSize() {
		return 8 * 2 + byteSize();
	}
}