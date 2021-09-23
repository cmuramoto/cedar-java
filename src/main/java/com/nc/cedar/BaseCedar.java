package com.nc.cedar;

import static com.nc.cedar.Bits.i32;
import static com.nc.cedar.Bits.u32;
import static com.nc.cedar.Bits.u64;
import static jdk.incubator.foreign.MemoryAccess.getByteAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getIntAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getLongAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setByteAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setIntAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setLongAtOffset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Stream;

import jdk.incubator.foreign.MemorySegment;

/**
 * Common structure and methods shared by {@link Cedar} and {@link ReducedCedar}. <br>
 * There's lots of code duplication in some methods/iterators in the subclasses due to the fact that
 * some specialized call sites, e.g., {@link Nodes#base(int)} and {@link Nodes#base_r(int)} run in
 * tight loops and we want to avoid virtual calls/branching as much as possible.
 *
 * @author cmuramoto
 */
@SuppressWarnings("preview")
public sealed abstract class BaseCedar permits Cedar,ReducedCedar {

	interface Factory<T extends BaseCedar> {
		T allocate(Nodes array, NodeInfos infos, Blocks blocks, Rejects reject, boolean ordered);
	}

	static final int BLOCK_TYPE_CLOSED = 0;
	static final int BLOCK_TYPE_OPEN = 1;
	static final int BLOCK_TYPE_FULL = 2;
	static final int VALUE_LIMIT = Integer.MAX_VALUE - 1;
	public static final int NO_VALUE_I32 = -1;
	public static final long NO_VALUE_U64 = NO_VALUE_I32 & 0xFFFFFFFFL;
	public static final long NO_VALUE = 1L << 31;
	public static final long ABSENT = 1L << 32;

	static final long ABSENT_OR_NO_VALUE = NO_VALUE | ABSENT;

	static void close(CedarBuffer c) {
		if (c != null) {
			c.close();
		}
	}

	static <T extends BaseCedar> T deserialize(Factory<T> factory, MemorySegment src, boolean copy) {
		var off = 0L;
		var ordered = getByteAtOffset(src, off) == 1;
		var blocks_head_full = getIntAtOffset(src, off += 1);
		var blocks_head_closed = getIntAtOffset(src, off += 4);
		var blocks_head_open = getIntAtOffset(src, off += 4);
		var max_trial = getIntAtOffset(src, off += 4);
		var capacity = getLongAtOffset(src, off += 4);
		var size = getLongAtOffset(src, off += 8);

		var array = new Nodes();
		array.pos = getLongAtOffset(src, off += 8);

		var infos = new NodeInfos();
		infos.pos = getLongAtOffset(src, off += 8);

		var blocks = new Blocks();
		blocks.pos = getLongAtOffset(src, off += 8);

		var rejects = new Rejects();
		rejects.pos = getLongAtOffset(src, off += 8);

		var lengths = new long[]{ //
				getLongAtOffset(src, off += 8), //
				getLongAtOffset(src, off += 8), //
				getLongAtOffset(src, off += 8), //
				getLongAtOffset(src, off += 8) //
		};

		var slices = Map.of( //
				array, src.asSlice(off += 8, lengths[0]), //
				infos, src.asSlice(off += lengths[0], lengths[1]), //
				blocks, src.asSlice(off += lengths[1], lengths[2]), //
				rejects, src.asSlice(off += lengths[2], lengths[3])//
		);

		for (var e : slices.entrySet()) {
			var cb = e.getKey();
			var ms = e.getValue();

			if (copy) {
				var seg = MemorySegment.allocateNative(ms.byteSize(), cb.alignment());
				seg.copyFrom(ms);
				ms = seg;
			}
			cb.buffer = ms;
		}

		var c = factory.allocate(array, infos, blocks, rejects, ordered);
		c.blocks_head_full = blocks_head_full;
		c.blocks_head_open = blocks_head_open;
		c.blocks_head_closed = blocks_head_closed;
		c.max_trial = max_trial;
		c.capacity = capacity;
		c.size = size;

		return c;
	}

	static <T extends BaseCedar> T deserialize(Factory<T> factory, Path src, boolean copy) {
		MemorySegment ms = null;
		try {
			ms = MemorySegment.mapFile(src, 0, Files.size(src), MapMode.READ_WRITE).share();
			return deserialize(factory, ms, copy);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			if (ms != null && copy) {
				ms.close();
			}
		}
	}

	static final long mask(long v) {
		return v == NO_VALUE ? NO_VALUE_U64 : v;
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

		return i32((size >> 8) - 1);
	}

	public final Map<String, Long> allocation() {
		return Map.of("array", array.byteSize(), "blocks", blocks.byteSize(), "infos", infos.byteSize(), "reject", reject.byteSize());
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

	public abstract long erase(String key);

	/**
	 * Returns the value associated with the key. The value will be an int if successful otherwise
	 * it will be either {@link BaseCedar#NO_VALUE} or {@link BaseCedar#ABSENT}. <br>
	 * Unlike rust, we use long to represent the result in order to avoid boxing the value into an
	 * {@link OptionalInt}. Once Valhalla is out, we can revert this back.
	 *
	 * @param key
	 * @return
	 */
	public abstract long find(String key);

	public abstract Match get(String key);

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
		var off = 0L;

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