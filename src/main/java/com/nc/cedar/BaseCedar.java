package com.nc.cedar;

import static com.nc.cedar.Bits.i32;
import static com.nc.cedar.Bits.u32;
import static com.nc.cedar.Bits.u64;
import static jdk.incubator.foreign.MemoryAccess.getIntAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getLongAtOffset;
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
 * There's lots of code duplication in some methods/iterators because we want to place the final
 * method calls at the specialized call sites, e.g., {@link Nodes#base(int)} and
 * {@link Nodes#base_r(int)} run in tight loops and this helps avoiding virtual calls/branching.
 *
 * @author cmuramoto
 */
@SuppressWarnings("preview")
public sealed abstract class BaseCedar permits Cedar,ReducedCedar {

	interface Factory<T extends BaseCedar> {
		T allocate(Nodes array, NodeInfos infos, Blocks blocks, Rejects reject, int flags);
	}

	static final int REALLOC_CAP = Integer.getInteger("Cedar.REALLOC_CAP", 0);

	static final int BLOCK_TYPE_CLOSED = 0;
	static final int BLOCK_TYPE_OPEN = 1;
	static final int BLOCK_TYPE_FULL = 2;
	static final int VALUE_LIMIT = Integer.MAX_VALUE - 1;

	public static final long NO_VALUE = 1L << 32;
	public static final long ABSENT = 1L << 33;
	public static final long ABSENT_OR_NO_VALUE = NO_VALUE | ABSENT;

	static long ceilPowerOfTwo(long v) {
		// capped to 1GB
		var max = 1L << 30;
		var n = -1L >>> Long.numberOfLeadingZeros(v - 1);
		return (n < 0) ? 1 : (n >= max ? max : n + 1);
	}

	static void close(CedarBuffer c) {
		if (c != null) {
			c.close();
		}
	}

	static int combine(boolean ordered, int realloc) {
		var rv = ordered ? 0x1 : 0;
		if (realloc > 0) {
			realloc = (int) ceilPowerOfTwo(realloc);
			rv |= (realloc << 1);
		}
		return rv;
	}

	static <T extends BaseCedar> T deserialize(Factory<T> factory, MemorySegment src, boolean copy) {
		var off = 0L;
		var flags = getIntAtOffset(src, off);
		var blocks_head_full = getIntAtOffset(src, off += 4);
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

		var c = factory.allocate(array, infos, blocks, rejects, flags);
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

	static void guardUpdate(byte[] key, long from, int start, int end) {
		if (from == 0 && end == 0 || (end > key.length || end < start)) {
			throw new UnsupportedOperationException("Invalid key/offsets");
		}
	}

	/**
	 * @param v
	 *            - 34 bit integer
	 * @return - true if v is not marked as either {@link BaseCedar#ABSENT} or
	 *         {@link BaseCedar#NO_VALUE}.
	 */
	public static boolean isValue(long v) {
		return (v & ABSENT_OR_NO_VALUE) != 0;
	}

	final Nodes array;

	final NodeInfos infos;
	final Blocks blocks;
	final Rejects reject;
	final int flags;
	int blocks_head_full;
	int blocks_head_closed;

	int blocks_head_open;

	int max_trial;

	long capacity;

	long size;

	protected BaseCedar(Nodes array, NodeInfos infos, Blocks blocks, Rejects reject, boolean ordered) {
		this(array, infos, blocks, reject, ordered, 0);
	}

	protected BaseCedar(Nodes array, NodeInfos infos, Blocks blocks, Rejects reject, boolean ordered, int realloc) {
		this(array, infos, blocks, reject, combine(ordered, realloc));
	}

	protected BaseCedar(Nodes array, NodeInfos infos, Blocks blocks, Rejects reject, int flags) {
		this.array = array;
		this.infos = infos;
		this.blocks = blocks;
		this.reject = reject;
		this.flags = flags;
	}

	final int add_block() {
		var cap = this.capacity;
		if (size == cap) {
			var r = realloc();
			var inc = r;

			if (r > 0) {
				if ((cap + r) > (cap + cap)) {
					inc = cap;
				} else {
					inc = r;
				}
			} else {
				inc = cap;
			}

			capacity = cap = cap + inc;

			array.resize(cap);
			infos.resize(cap);

			if (r > 0) {
				var bc = blocks.cap();
				var req = ceilPowerOfTwo(cap >> 8);

				if (bc < req) {
					r = req;
				} else {
					// blocks need only to be as big as cap >> 8
					r = 0;
				}
			} else {
				r = cap >> 8;
			}

			if (r > 0) {
				blocks.resize(r);
			}
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

	/**
	 * Number of bytes allocated for each internal 'array' and reallocation policy.
	 *
	 * @return
	 */
	public final Map<String, Long> allocation() {
		return Map.of("array", array.byteSize(), "blocks", blocks.byteSize(), "infos", infos.byteSize(), "reject", reject.byteSize(), "realloc", realloc());
	}

	/**
	 * Inserts keys in the trie as if
	 *
	 * <pre>
	 * <code>
	 *   for(var e : kv) {
	 *     update(e.getKey(), e.getValue());
	 *   }
	 * </code>
	 * </pre>
	 *
	 * @param keys
	 *            - dictionary
	 */
	public abstract <E extends Map.Entry<String, Integer>> void build(Iterable<E> kv);

	/**
	 * Inserts keys in the trie as if
	 *
	 * <pre>
	 * <code>
	 *   for(var e : map.entrySet()) {
	 *     update(e.getKey(), e.getValue());
	 *   }
	 * </code>
	 * </pre>
	 *
	 * @param keys
	 *            - dictionary
	 */
	public abstract void build(Map<String, Integer> key_values);

	/**
	 * Inserts keys in the trie as if
	 *
	 * <pre>
	 * <code>
	 *   for(var i=0; i < keys.length; i++) {
	 *     update(keys[i], i);
	 *   }
	 * </code>
	 * </pre>
	 *
	 * @param keys
	 *            - dictionary
	 */
	public abstract void build(String... keys);

	/**
	 * Releases the allocated memory. This instance will be unusable afterwards.
	 */
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

	/**
	 * Marks the key as absent in the trie.
	 *
	 * @param key
	 *            - utf8 encoded string
	 * @return - The value associated with {@link BaseCedar#find(byte[])}. Erase has no side effect
	 *         if the reported value is either {@link BaseCedar#NO_VALUE} or
	 *         {@link BaseCedar#ABSENT}.
	 */
	public abstract long erase(byte[] key);

	/**
	 * @param key
	 * @return @see {@link BaseCedar#erase(byte[])}
	 */
	public abstract long erase(String key);

	/**
	 * @param key
	 * @return {@link BaseCedar#find(byte[], int, int)}, with start=0 and end=key.length
	 */
	public abstract long find(byte[] key);

	/**
	 * Find's the associated value with the slice [start,end) of the key. Unlike rust, we use long
	 * to represent the result in order to avoid boxing the value into an {@link OptionalInt}. Once
	 * Valhalla is out, we can revert this back.
	 *
	 * @param key
	 *            - utf8 encoded key
	 * @return - A 32-bit value if key is present otherwise may return either
	 *         {@link BaseCedar#NO_VALUE} if the key exists as a prefix (all bytes are there, but do
	 *         not form a whole word, e.g. "banana" exists and query was "banan") or
	 *         {@link BaseCedar#ABSENT} if there's a single byte that does not match the trie at any
	 *         level (e.g. "banana" exists and query is "badana").
	 */
	public abstract long find(byte[] key, int start, int end);

	/**
	 * Delegates to {@link BaseCedar#find(byte[])}, by converting the key using
	 * {@link Bits#utf8(String)}. This method is not final because we want the call to
	 * {@link BaseCedar#find(byte[])} to be placed in the respective implementation's call sites.
	 *
	 * @param key
	 * @return
	 */
	public abstract long find(String key);

	/**
	 * Exact lookup.
	 *
	 * @param key
	 *            - utf8 encoded String.
	 * @return - Returns a {@link Match}, if the key exists in the dictionary otherwise null. This
	 *         method is meant to be used instead of find when the key needs to be rebuilt by
	 *         calling {@link BaseCedar#suffix(Match)}.
	 */
	public abstract Match get(byte[] key);

	/**
	 * Delegates to {@link BaseCedar#get(byte[])}, by converting the key using
	 * {@link Bits#utf8(String)}.
	 *
	 * @param key
	 *            - string
	 * @return - {@link BaseCedar#get(byte[]) }
	 */
	public abstract Match get(String key);

	final int get_head(int type) {
		return switch (type) {
		case BLOCK_TYPE_OPEN -> blocks_head_open;
		case BLOCK_TYPE_CLOSED -> blocks_head_closed;
		case BLOCK_TYPE_FULL -> blocks_head_full;
		default -> throw new Error("Invalid BlockType: " + type);
		};
	}

	/**
	 * @return Total bytes required to serialize this trie.
	 */
	public long imageSize() {
		return 4 * 5 + 8 * 2 + array.totalSize() + infos.totalSize() + blocks.totalSize() + reject.totalSize();
	}

	public final boolean isReduced() {
		return this instanceof ReducedCedar;
	}

	final boolean ordered() {
		return (flags & 0x1) == 0;
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

	/**
	 * @param key
	 * @return - All terminal nodes that share key as common prefix.
	 */
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

	final void push_e_node_mini(int e) {
		var idx = e >> 6;
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
		var ordered = ordered();
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

	final long realloc() {
		return (flags >>> 1) & 0xFFFFFFFFL;
	}

	/**
	 * At every offset in [0,...,text.length()] returns matching prefixes. <br>
	 * This works like using a regex built with exact terms to find all matches in a text, and can
	 * be thought as
	 *
	 * <pre>
	 * </code>
	 *   for(var i = 0; i < text.length();i++) {
	 *   	yield predict(text.substring(i, i + 1));
	 *   }
	 * </code>
	 * </pre>
	 *
	 * but it's much more efficient.
	 *
	 * @param text
	 * @return
	 */
	public abstract Stream<TextMatch> scan(String text);

	public void serialize(MemorySegment dst) {
		var off = 0L;

		setIntAtOffset(dst, off, flags);
		setIntAtOffset(dst, off += 4, blocks_head_full);
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

	/**
	 * Returns the associated suffix with a match.
	 *
	 * <pre>
	 * <code>
	 *   var cedar = new Cedar();
	 *   cedar.update("banana", 0);
	 *   var matches = cedar.predict("ba");
	 *
	 *   matches.map(cedar::suffix).toArray(); // ["nana"]
	 * </code>
	 * </pre>
	 *
	 * @param m
	 * @return
	 */
	public abstract String suffix(Match m);

	final void transfer_block(int idx, int from, int to, boolean toBlockEmpty) {
		var isLast = idx == blocks.next(idx);
		var isEmpty = toBlockEmpty && blocks.num(idx) != 0;

		pop_block(idx, from, isLast);
		push_block(idx, to, isEmpty);
	}

	/**
	 * Delegates to {@link BaseCedar#update(byte[], int, int, int)}, with start=0 and end=key.length
	 *
	 * @param utf8
	 * @param value
	 * @return
	 */
	public abstract int update(byte[] utf8, int value);

	public abstract int update(byte[] utf8, int value, int start, int end);

	public abstract int update(String key, int value);

	public abstract Stream<Match> withCommonPrefix(String key);
}