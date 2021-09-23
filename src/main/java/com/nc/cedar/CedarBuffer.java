package com.nc.cedar;

import static com.nc.cedar.Bits.u64;
import static jdk.incubator.foreign.MemoryAccess.getByteAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getIntAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getShortAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setByteAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setIntAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setShortAtOffset;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.LongStream;

import jdk.incubator.foreign.MemorySegment;

final class Blocks extends CedarBuffer {
	static final long UNIT = 20;

	static Blocks initial() {
		var blocks = new Blocks(1);
		blocks.pushDefault();
		blocks.head(0, 1);

		return blocks;
	}

	Blocks() {
	}

	Blocks(long cap) {
		super(cap, UNIT);
	}

	@Override
	long alignment() {
		return 32;
	}

	int head(int ix) {
		return head(u64(ix));
	}

	void head(int ix, int value) {
		head(u64(ix), value);
	}

	int head(long ix) {
		return getIntAtOffset(buffer, safeOffset(ix) + 16);
	}

	void head(long ix, int value) {
		setIntAtOffset(buffer, safeOffset(ix) + 16, value);
	}

	void incrementNum(int ix, int inc) {
		incrementNum(u64(ix), inc);
	}

	void incrementNum(long ix, int inc) {
		var curr = num(ix);
		num(ix, (short) (curr + inc));
	}

	int next(int ix) {
		return next(u64(ix));
	}

	void next(int ix, int v) {
		next(u64(ix), v);
	}

	int next(long ix) {
		return getIntAtOffset(buffer, safeOffset(ix) + 4);
	}

	void next(long ix, int v) {
		setIntAtOffset(buffer, safeOffset(ix) + 4, v);
	}

	short num(int ix) {
		return num(u64(ix));
	}

	void num(int ix, short v) {
		num(u64(ix), v);
	}

	short num(long ix) {
		return getShortAtOffset(buffer, safeOffset(ix) + 8);
	}

	void num(long ix, short v) {
		setShortAtOffset(buffer, safeOffset(ix) + 8, v);
	}

	long offset() {
		return pos * UNIT;
	}

	int prev(int ix) {
		return prev(u64(ix));
	}

	void prev(int ix, int v) {
		prev(u64(ix), v);
	}

	int prev(long ix) {
		return getIntAtOffset(buffer, safeOffset(ix));
	}

	void prev(long ix, int v) {
		setIntAtOffset(buffer, safeOffset(ix), v);
	}

	void push(int prev, int next, short num, short reject, int trial, int head) {
		require(1);
		var off = offset();
		var buffer = this.buffer;
		setIntAtOffset(buffer, off, prev);
		setIntAtOffset(buffer, off + 4, next);
		setShortAtOffset(buffer, off + 8, num);
		setShortAtOffset(buffer, off + 10, reject);
		setIntAtOffset(buffer, off + 12, trial);
		setIntAtOffset(buffer, off + 16, head);

		this.pos++;
	}

	void pushDefault() {
		push(0, 0, (short) 256, (short) 257, 0, 0);
	}

	short reject(int ix) {
		return reject(u64(ix));
	}

	void reject(int ix, short v) {
		reject(u64(ix), v);
	}

	short reject(long ix) {
		return getShortAtOffset(buffer, safeOffset(ix) + 10);
	}

	void reject(long ix, short v) {
		setShortAtOffset(buffer, safeOffset(ix) + 10, v);
	}

	void require(long n) {
		if ((pos + n) > cap(UNIT)) {
			grow(n, UNIT);
		}
	}

	void resize(long newLen) {
		super.resize(newLen, UNIT);
	}

	long safeOffset(long ix) {
		return safeOffset(ix, UNIT);
	}

	@Override
	void set(MemorySegment buffer, long off) {
		setIntAtOffset(buffer, off, 0);
		setIntAtOffset(buffer, off + 4, 0);
		setShortAtOffset(buffer, off + 8, (short) 256);
		setShortAtOffset(buffer, off + 10, (short) 257);
		setIntAtOffset(buffer, off + 12, 0);
		setIntAtOffset(buffer, off + 16, 0);
	}

	@Override
	public String toString() {
		var objs = LongStream.range(0, pos)//
				.mapToObj(ix -> Map.of(//
						"prev", prev(ix), "next", next(ix), //
						"num", num(ix), "reject", reject(ix), //
						"trial", trial(ix), "head", head(ix)))
				.toArray();
		return Arrays.toString(objs);
	}

	int trial(int ix) {
		return trial(u64(ix));
	}

	void trial(int ix, int v) {
		trial(u64(ix), v);
	}

	int trial(long ix) {
		return getIntAtOffset(buffer, safeOffset(ix) + 12);
	}

	void trial(long ix, int v) {
		setIntAtOffset(buffer, safeOffset(ix) + 12, v);
	}
}

/**
 * Base storage for cedar. Abstract methods are non-performance critical.
 *
 * @author cmuramoto
 */
abstract class CedarBuffer {

	static final boolean BOUNDS_CHECK = true;

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

final class NodeInfos extends CedarBuffer {
	static final long UNIT = 2;

	static NodeInfos initial() {
		var infos = new NodeInfos(256);
		infos.fill(0);
		infos.jump(UNIT);
		return infos;
	}

	NodeInfos() {
	}

	NodeInfos(long cap) {
		super(cap, UNIT);
	}

	@Override
	long alignment() {
		return UNIT;
	}

	byte child(int ix) {
		return child(u64(ix));
	}

	void child(int ix, byte v) {
		child(u64(ix), v);
	}

	byte child(long ix) {
		return getByteAtOffset(buffer, safeOffset(ix) + 1);
	}

	void child(long ix, byte v) {
		setByteAtOffset(buffer, safeOffset(ix) + 1, v);
	}

	long offset() {
		return pos << 1;
	}

	void push(byte sibling, byte child) {
		require(1);
		var off = offset();
		setByteAtOffset(buffer, off, sibling);
		setByteAtOffset(buffer, off + 1, child);

		pos++;
	}

	void require(long n) {
		if ((pos + n) > cap(UNIT)) {
			grow(n, UNIT);
		}
	}

	void resize(long newLen) {
		super.resize(newLen, UNIT);
	}

	long safeOffset(long ix) {
		return safeOffset(ix, UNIT);
	}

	void set(int ix, byte sibling, byte child) {
		set(u64(ix), sibling, child);
	}

	void set(long ix, byte sibling, byte child) {
		var off = safeOffset(ix);
		setByteAtOffset(buffer, off, sibling);
		setByteAtOffset(buffer, off + 1, child);
	}

	@Override
	public void set(MemorySegment buffer, long off) {
		setShortAtOffset(buffer, off, (short) 0);
	}

	byte sibling(int ix) {
		return sibling(u64(ix));
	}

	void sibling(int ix, byte v) {
		sibling(u64(ix), v);
	}

	byte sibling(long ix) {
		return getByteAtOffset(buffer, safeOffset(ix));
	}

	void sibling(long ix, byte v) {
		setByteAtOffset(buffer, safeOffset(ix), v);
	}
}

final class Nodes extends CedarBuffer {

	static final long UNIT = 8;

	static Nodes initial() {
		var array = new Nodes(256);
		array.push(0, -1);
		for (var i = 1; i < 256; i++) {
			array.push(-(i - 1), -(i + 1));
		}
		array.base(1, -255);
		array.check(255, -1);
		return array;
	}

	static Nodes initial_r() {
		var array = new Nodes(256);
		array.push(-1, -1);
		for (var i = 1; i < 256; i++) {
			array.push(-(i - 1), -(i + 1));
		}
		array.base(1, -255);
		array.check(255, -1);
		return array;
	}

	Nodes() {
	}

	Nodes(long cap) {
		super(cap, UNIT);
	}

	@Override
	long alignment() {
		return 8;
	}

	int base(int ix) {
		return base(u64(ix));
	}

	void base(int ix, int v) {
		base(u64(ix), v);
	}

	int base(long ix) {
		return getIntAtOffset(buffer, safeOffset(ix));
	}

	void base(long ix, int v) {
		setIntAtOffset(buffer, safeOffset(ix), v);
	}

	int base_r(int ix) {
		return base_r(u64(ix));
	}

	int base_r(long ix) {
		return -(getIntAtOffset(buffer, safeOffset(ix)) + 1);
	}

	int check(int ix) {
		return check(u64(ix));
	}

	void check(int ix, int v) {
		check(u64(ix), v);
	}

	int check(long ix) {
		return getIntAtOffset(buffer, safeOffset(ix) + 4);
	}

	void check(long ix, int v) {
		setIntAtOffset(buffer, safeOffset(ix) + 4, v);
	}

	int getAndSetBase(long ix, int v) {
		var off = safeOffset(ix);
		var rv = getIntAtOffset(buffer, off);
		setIntAtOffset(buffer, off, v);
		return rv;
	}

	long offset() {
		return pos << 3;
	}

	void push(int base, int check) {
		require(1);
		var off = offset();
		var b = buffer;
		setIntAtOffset(b, off, base);
		setIntAtOffset(b, off + 4, check);
		pos++;
	}

	void require(long n) {
		if ((pos + n) > cap(UNIT)) {
			grow(n, UNIT);
		}
	}

	void resize(long newLen) {
		super.resize(newLen, UNIT);
	}

	long safeOffset(long ix) {
		return safeOffset(ix, UNIT);
	}

	void set(int ix, int base, int check) {
		set(u64(ix), base, check);
	}

	void set(long ix, int base, int check) {
		var off = safeOffset(ix);
		setIntAtOffset(buffer, off, base);
		setIntAtOffset(buffer, off + 4, check);
	}

	@Override
	void set(MemorySegment buffer, long off) {
		setIntAtOffset(buffer, off, 0);
		setIntAtOffset(buffer, off + 4, 0);
	}

	@Override
	public String toString() {
		var objs = LongStream.range(0, pos).mapToObj(ix -> Map.of("base", base(ix), "check", check(ix))).toArray();
		return Arrays.toString(objs);
	}

}

/**
 * Poor man's pointer to emulate pass by ref
 */
final class Ptr {
	long v;
}

final class Rejects extends CedarBuffer {
	static final long UNIT = 2;

	static Rejects initial() {
		var reject = new Rejects(257);
		for (short j = 0; j < 257; j++) {
			reject.push((short) (j + 1));
		}
		return reject;
	}

	Rejects() {
	}

	Rejects(long cap) {
		super(cap, UNIT);
	}

	@Override
	long alignment() {
		return 2;
	}

	short at(long ix) {
		return getShortAtOffset(buffer, safeOffset(ix));
	}

	long offset() {
		return pos << 1;
	}

	void push(short r) {
		require(1);
		var off = offset();
		var b = buffer;
		setShortAtOffset(b, off, r);
		pos++;
	}

	void require(long n) {
		if ((pos + n) > cap(UNIT)) {
			grow(n, UNIT);
		}
	}

	long safeOffset(long ix) {
		return safeOffset(ix, UNIT);
	}

	void set(long ix, short v) {
		setShortAtOffset(buffer, safeOffset(ix), v);
	}

	@Override
	void set(MemorySegment buffer, long offset) {
		setShortAtOffset(buffer, offset, (short) 0);
	}

	short[] toArray() {
		var rv = new short[(int) this.pos];

		for (var i = 0; i < rv.length; i++) {
			rv[i] = at(i);
		}

		return rv;
	}
}

/**
 * Instead of allocating a tuple, we pass this state as a parameter to trap return values.
 */
interface Scratch {
	void set(long from, long p, long value);
}