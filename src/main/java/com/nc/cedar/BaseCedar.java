package com.nc.cedar;

import static com.nc.cedar.Bits.i32;
import static com.nc.cedar.Bits.u32;
import static com.nc.cedar.Bits.u64;
import static jdk.incubator.foreign.MemoryAccess.setByteAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setIntAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setLongAtOffset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import jdk.incubator.foreign.MemorySegment;

@SuppressWarnings("preview")
public sealed abstract class BaseCedar permits Cedar,ReducedCedar {

	static final int BLOCK_TYPE_CLOSED = 0;
	static final int BLOCK_TYPE_OPEN = 1;
	static final int BLOCK_TYPE_FULL = 2;
	static final int CEDAR_VALUE_LIMIT = Integer.MAX_VALUE - 1;
	static final long NO_VALUE = 1L << 32;
	static final long ABSENT = 1L << 33;
	static final long ABSENT_OR_NO_VALUE = NO_VALUE | ABSENT;

	static void close(CedarBuffer c) {
		if (c != null) {
			c.close();
		}
	}

	final Nodes array;
	final NodeInfos infos;
	final Blocks blocks;
	final boolean ordered;

	final Rejects reject;
	int blocks_head_full;
	int blocks_head_closed;
	int blocks_head_open;
	int max_trial;
	long capacity;
	long size;

	protected BaseCedar(Nodes array, NodeInfos infos, Blocks blocks, Rejects reject, boolean ordered) {
		this.array = array;
		this.infos = infos;
		this.blocks = blocks;
		this.reject = reject;
		this.ordered = ordered;
	}

	final int add_block() {
		if (size == capacity) {
			capacity += capacity;
			array.resize(capacity);
			infos.resize(capacity);
			blocks.resize(capacity >> 8);
		}

		blocks.head(size >> 8, i32(size));

		// make it a doubley linked list
		array.set(size, -(i32(size) + 255), -(i32(size) + 1));

		for (var i = (size + 1); i < (size + 255); i++) {
			array.set(i, -(i32(i) - 1), -(i32(i) + 1));
		}

		array.set(size + 255, -(i32(size) + 254), -i32(size));

		var is_empty = blocks_head_open == 0;
		var idx = i32(size >> 8);
		assert (blocks.num(idx) > 1);
		push_block(idx, BLOCK_TYPE_OPEN, is_empty);

		size += 256;

		return i32(((size >> 8) - 1));
	}

	public abstract <E extends Map.Entry<String, Integer>> void build(Iterable<E> kv);

	public abstract void build(Map<String, Integer> key_values);

	public abstract void build(String... keys);

	public void close() {
		close(array);
		close(infos);
		close(blocks);
		close(reject);
	}

	// for rust test suite only, clients should use streams
	abstract Iterator<Match> common_prefix_iter(String key);

	final boolean consult(int base_n, int base_p, byte c_n, byte c_p) {
		do {
			c_n = infos.sibling(base_n ^ u32(c_n));
			c_p = infos.sibling(base_p ^ u32(c_p));
		} while (c_n != 0 && c_p != 0);

		return c_p != 0;
	}

	public abstract void erase(String key);

	public abstract Match get(String str);

	final int get_head(int type) {
		return switch (type) {
		case BLOCK_TYPE_OPEN -> blocks_head_open;
		case BLOCK_TYPE_CLOSED -> blocks_head_closed;
		case BLOCK_TYPE_FULL -> blocks_head_full;
		default -> throw new Error("Invalid BlockType: " + type);
		};
	}

	public long imageSize() {
		return 4 * 4 + 1 + 8 * 2 + array.totalSize() + infos.totalSize() + blocks.totalSize() + reject.totalSize();
	}

	public final boolean isReduced() {
		return this instanceof ReducedCedar;
	}

	final void pop_block(int idx, int from, boolean last) {
		int head;
		if (last) {
			head = 0;
		} else {
			head = get_head(from);
			var next = blocks.next(idx);
			var prev = blocks.prev(idx);
			blocks.next(prev, next);
			blocks.prev(next, prev);

			if (idx == head) {
				head = next;
			}
		}

		set_head(from, head);
	}

	final void pop_sibling(int from, int base, byte label) {
		var ix = u64(from);
		var c = infos.child(ix);
		var sibling = c != label;

		if (sibling) {
			do {
				var code = u32(c);
				c = infos.sibling(ix = u64(base ^ code));
			} while (c != label);
		}

		var code = u32(label);
		c = infos.sibling(base ^ code);
		if (sibling) {
			infos.sibling(ix, c);
		} else {
			infos.child(ix, c);
		}

	}

	public abstract Stream<Match> predict(String key);

	final void push_block(int idx, int to, boolean empty) {
		var head = get_head(to);

		if (empty) {
			blocks.next(idx, idx);
			blocks.prev(idx, idx);
			head = idx;
		} else {
			blocks.prev(idx, blocks.prev(head));
			blocks.next(idx, head);
			var t = blocks.prev(head);
			blocks.next(t, idx);
			blocks.prev(head, idx);
			head = idx;
		}

		set_head(to, head);
	}

	final void push_e_node(int e) {
		var idx = e >> 8;
		blocks.incrementNum(idx, 1);

		if (blocks.num(idx) == 1) {
			blocks.head(idx, e);
			array.set(e, -e, -e);

			if (idx != 0) {
				// Move the block from 'Full' to 'Closed' since it has one free slot now.
				transfer_block(idx, BLOCK_TYPE_FULL, BLOCK_TYPE_CLOSED, blocks_head_closed == 0);
			}
		} else {
			var prev = blocks.head(idx);

			var next = -array.check(prev);

			// Insert to the edge immediately after the e_head
			array.set(e, -prev, -next);

			array.check(prev, -e);
			array.base(next, -e);

			// Move the block from 'Closed' to 'Open' since it has more than one free slot now.
			if (blocks.num(idx) == 2 || blocks.trial(idx) == max_trial) {
				assert (blocks.num(idx) > 1);
				if (idx != 0) {
					transfer_block(idx, BLOCK_TYPE_CLOSED, BLOCK_TYPE_OPEN, blocks_head_open == 0);
				}
			}

			// Reset the trial stats
			blocks.trial(idx, 0);
		}

		var rej = reject.at(blocks.num(idx));
		if (blocks.reject(idx) < rej) {
			blocks.reject(idx, rej);
		}

		infos.set(e, (byte) 0, (byte) 0);
	}

	final void push_sibling(long from, int base, byte label, boolean hasChild) {
		boolean keep_order;

		if (ordered) {
			keep_order = label > infos.child(from);
		} else {
			keep_order = infos.child(from) == 0;
		}

		byte sibling;
		var c_ix = from;
		var c = infos.child(c_ix);
		var isSibling = hasChild && keep_order;

		if (isSibling) {
			do {
				c = infos.sibling(c_ix = u64(base ^ u32(c)));
			} while ((ordered && (c != 0) && (c < label)));
		}

		sibling = c;
		c = label;

		if (isSibling) {
			infos.sibling(c_ix, c);
		} else {
			infos.child(c_ix, c);
		}

		infos.sibling(base ^ u32(label), sibling);
	}

	public abstract Stream<TextMatch> scan(String text);

	public void serialize(MemorySegment dst) {
		var off = 0;

		setByteAtOffset(dst, off, (byte) (ordered ? 1 : 0));
		setIntAtOffset(dst, off += 1, blocks_head_full);
		setIntAtOffset(dst, off += 4, blocks_head_closed);
		setIntAtOffset(dst, off += 4, blocks_head_open);
		setIntAtOffset(dst, off += 4, max_trial);
		setLongAtOffset(dst, off += 4, capacity);
		setLongAtOffset(dst, off += 8, size);

		setLongAtOffset(dst, off += 8, array.pos);
		setLongAtOffset(dst, off += 8, infos.pos);
		setLongAtOffset(dst, off += 8, blocks.pos);
		setLongAtOffset(dst, off += 8, reject.pos);

		setLongAtOffset(dst, off += 8, array.byteSize());
		setLongAtOffset(dst, off += 8, infos.byteSize());
		setLongAtOffset(dst, off += 8, blocks.byteSize());
		setLongAtOffset(dst, off += 8, reject.byteSize());

		dst.asSlice(off += 8, array.byteSize()).copyFrom(array.buffer);
		dst.asSlice(off += array.byteSize(), infos.byteSize()).copyFrom(infos.buffer);
		dst.asSlice(off += infos.byteSize(), blocks.byteSize()).copyFrom(blocks.buffer);
		dst.asSlice(off += blocks.byteSize(), reject.byteSize()).copyFrom(reject.buffer);
	}

	public void serialize(Path dst) {
		try (var ms = MemorySegment.mapFile(dst, 0, imageSize(), MapMode.READ_WRITE)) {
			serialize(ms);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	final void set_head(int type, int head) {
		switch (type) {
		case BLOCK_TYPE_OPEN:
			blocks_head_open = head;
			break;
		case BLOCK_TYPE_CLOSED:
			blocks_head_closed = head;
			break;
		case BLOCK_TYPE_FULL:
			blocks_head_full = head;
			break;
		default:
			throw new IllegalArgumentException("Unexpected value: " + type);
		}
	}

	public abstract String suffix(long to, int len);

	final void transfer_block(int idx, int from, int to, boolean toBlockEmpty) {
		var isLast = idx == blocks.next(idx);
		var isEmpty = toBlockEmpty && blocks.num(idx) != 0;

		pop_block(idx, from, isLast);
		push_block(idx, to, isEmpty);
	}

	public abstract void update(String key, int value);

	public abstract Stream<Match> withCommonPrefix(String key);

}
