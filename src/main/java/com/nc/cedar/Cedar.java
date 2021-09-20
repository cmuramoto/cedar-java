package com.nc.cedar;

import static com.nc.cedar.Bits.utf8;
import static jdk.incubator.foreign.MemoryAccess.getByteAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getIntAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getLongAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getShortAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setByteAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setIntAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setLongAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setShortAtOffset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jdk.incubator.foreign.MemorySegment;

public class Cedar /* implements AutoCloseable */ {

	abstract class BaseItr<T> implements Iterator<T> {

		T curr;

		abstract void advance();

		@Override
		public final boolean hasNext() {
			if (curr == null) {
				advance();
			}
			return curr != null;
		}

		@Override
		public final T next() {
			var rv = curr;
			if (rv == null) {
				throw new IllegalStateException();
			}
			curr = null;

			return rv;
		}

		final Stream<T> stream() {
			return StreamSupport.stream(Spliterators.spliteratorUnknownSize(this, Spliterator.NONNULL), false);
		}
	}

	static final class Blocks extends CedarBuffer {
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
			if ((pos + n) >= cap(UNIT)) {
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

	static final class NodeInfos extends CedarBuffer {
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
			if ((pos + n) >= cap(UNIT)) {
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

	static final class Nodes extends CedarBuffer {

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
			if ((pos + n) >= cap(UNIT)) {
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

	final class PrefixIter extends BaseItr<Match> {

		final byte[] key;
		final Ptr from;
		int i;

		PrefixIter(byte[] key) {
			this.key = key;
			this.from = new Ptr();
		}

		@Override
		void advance() {
			while (i < key.length) {
				var value = find(key, from, i, i + 1);

				if (value.isPresent()) {
					if (value == CEDAR_NO_VALUE_OP) {
						i++;
						continue;
					} else {
						curr = new Match(value.getAsInt(), i, from.v);
						i++;
						break;
					}
				} else {
					break;
				}
			}
		}
	}

	final class PrefixPredictIter extends BaseItr<Match> implements Scratch {

		final byte[] key;
		final Ptr from;
		long p;
		long root;
		OptionalInt value = OptionalInt.empty();

		PrefixPredictIter(byte[] key) {
			this.key = key;
			from = new Ptr();
		}

		@Override
		void advance() {
			if (value == null) {
				return;
			}

			if (from.v == 0 && p == 0) {
				// To locate the prefix's position first, if it doesn't exist then that means we
				// don't have do anything. `from` would serve as the cursor.

				if (key.length == 0 || find(key, from).isPresent()) {
					this.root = this.from.v;

					begin(this.from.v, this.p, this);

					tryAdvance();
				}
			} else {
				tryAdvance();
			}
		}

		@Override
		public long from() {
			return from.v;
		}

		@Override
		public void from(long v) {
			this.from.v = v;
		}

		@Override
		public long p() {
			return p;
		}

		@Override
		public void p(long p) {
			this.p = p;
		}

		void tryAdvance() {
			if (value.isPresent()) {
				var result = new Match(value.getAsInt(), (int) p, from());

				Cedar.this.next(from.v, p, root, this);

				curr = result;
				if (value.isEmpty()) {
					value = null;
				}
			} else {
				curr = null;
				value = null;
			}
		}

		@Override
		public OptionalInt value() {
			return value;
		}

		@Override
		public void value(OptionalInt value) {
			this.value = value;
		}

	}

	static final class Ptr {
		long v;
	}

	static final class Rejects extends CedarBuffer {
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
			if ((pos + n) >= cap(UNIT)) {
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

	final class ScanItr extends BaseItr<TextMatch> {

		final byte[] text;
		final Ptr from;
		int base;
		int i;

		ScanItr(byte[] text, int base) {
			super();
			this.text = text;
			from = new Ptr();
			this.base = base;
		}

		@Override
		void advance() {
			for (; base < text.length; base++) {
				var limit = text.length - base;

				while (i < limit) {
					var off = base + i;

					var r = find(text, from, off, off + 1);

					if (r.isPresent()) {
						if (r == CEDAR_NO_VALUE_OP) {
							i++;
							continue;
						} else {
							curr = new TextMatch(base, base + i + 1, r.getAsInt());
							i++;
							return;
						}
					} else {
						break;
					}
				}

				i = 0;
				from.v = 0;
			}
		}
	}

	interface Scratch {
		long from();

		void from(long v);

		long p();

		void p(long p);

		OptionalInt value();

		default void value(int value) {
			value(OptionalInt.of(value));
		}

		void value(OptionalInt value);
	}

	static final Charset UTF8 = Charset.forName("UTF-8");

	static final int BLOCK_TYPE_CLOSED = 0;

	static final int BLOCK_TYPE_OPEN = 1;

	static final int BLOCK_TYPE_FULL = 2;

	static final int CEDAR_NO_VALUE = -1;

	static final OptionalInt CEDAR_NO_VALUE_OP = OptionalInt.of(CEDAR_NO_VALUE);

	static void close(CedarBuffer c) {
		if (c != null) {
			c.close();
		}
	}

	public static Cedar deserialize(MemorySegment src, boolean copy) {
		var off = 0;
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

		var c = new Cedar(array, infos, blocks, rejects);
		c.ordered = ordered;
		c.blocks_head_full = blocks_head_full;
		c.blocks_head_open = blocks_head_open;
		c.blocks_head_closed = blocks_head_closed;
		c.max_trial = max_trial;
		c.capacity = capacity;
		c.size = size;

		return c;
	}

	public static Cedar deserialize(Path src, boolean copy) {
		MemorySegment ms = null;
		try {
			ms = MemorySegment.mapFile(src, 0, Files.size(src), MapMode.READ_WRITE);
			return deserialize(ms, copy);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			if (ms != null && copy) {
				ms.close();
			}
		}
	}

	static int i32(long v) {
		return (int) v;
	}

	static int u32(byte v) {
		return v & 0xFF;
	}

	static long u64(int n) {
		return n & 0XFFFFFFFFL;
	}

	final Nodes array;
	final NodeInfos infos;

	final Blocks blocks;
	final Rejects reject;
	int blocks_head_full;
	int blocks_head_closed;
	int blocks_head_open;
	int max_trial;
	long capacity;

	long size;

	boolean ordered;

	public Cedar() {
		this(Nodes.initial(), NodeInfos.initial(), Blocks.initial(), Rejects.initial());

		capacity = 256;
		size = 256;
		ordered = true;
		max_trial = 1;
	}

	private Cedar(Nodes array, NodeInfos infos, Blocks blocks, Rejects reject) {
		this.array = array;
		this.infos = infos;
		this.reject = reject;
		this.blocks = blocks;
	}

	int add_block() {
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

	private void begin(long from, long p, Scratch s) {
		var array = this.array;
		var infos = this.infos;
		var base = array.base(from);

		var c = infos.child(from);

		if (from == 0) {
			c = infos.sibling(base ^ u32(c));

			// if no sibling couldn be found from the virtual root, then we are done.
			if (c == 0) {
				s.value(OptionalInt.empty());
				s.from(from);
				s.p(p);
				return;
			}
		}

		// recursively traversing down to look for the first leaf.
		while (c != 0) {
			from = u64(array.base(from) ^ u32(c));
			c = infos.child(from);
			p += 1;
		}

		// To return the value of the leaf.
		var v = array.base(array.base(from) ^ u32(c));
		s.value(v);
		s.from(from);
		s.p(p);
	}

	public void build(byte[]... keys) {
		for (var i = 0; i < keys.length; i++) {
			update(keys[i], i);
		}
	}

	public void build(Collection<Map.Entry<String, Integer>> kv) {
		for (var e : kv) {
			update(e.getKey(), e.getValue());
		}
	}

	public void build(Map<String, Integer> kv) {
		for (var e : kv.entrySet()) {
			update(e.getKey(), e.getValue());
		}
	}

	public void build(String... keys) {
		for (var i = 0; i < keys.length; i++) {
			update(keys[i], i);
		}
	}

	public long byteSize() {
		return 4 * 4 + 1 + 8 * 2 + array.totalSize() + infos.totalSize() + blocks.totalSize() + reject.totalSize();
	}

	// @Override
	public void close() {
		close(array);
		close(infos);
		close(blocks);
		close(reject);
	}

	Iterator<Match> common_prefix_iter(byte[] utf8) {
		return new PrefixIter(utf8);
	}

	Iterator<Match> common_prefix_iter(String key) {
		return common_prefix_iter(utf8(key));
	}

	boolean consult(int base_n, int base_p, byte c_n, byte c_p) {
		for (;;) {
			c_n = infos.sibling(base_n ^ u32(c_n));
			c_p = infos.sibling(base_p ^ u32(c_p));

			if (!(c_n != 0 && c_p != 0)) {
				break;
			}
		}

		return c_p != 0;
	}

	private void erase(byte[] key) {
		var from = new Ptr();

		if (find(key, from).isPresent()) {
			erase(from.v);
		}
	}

	private void erase(long from) {
		var e = array.base(from);
		var has_sibling = false;
		do {
			// var n = array.at(v);
			var base = array.base(from);
			has_sibling = infos.sibling(base ^ u32(infos.child(from))) != 0;

			// if the node has siblings, then remove `e` from the sibling.
			if (has_sibling) {
				pop_sibling((int) from, base, (byte) (base ^ e));
			}

			// maintain the data structures.
			push_e_node(e);
			e = (int) from;

			// traverse to the parent.
			from = array.check(from);

			// if it has sibling then this layer has more than one nodes, then we are done.
		} while (!has_sibling);
	}

	public void erase(String key) {
		erase(utf8(key));
	}

	OptionalInt find(byte[] key, Ptr from) {
		return find(key, from, 0, key.length);
	}

	OptionalInt find(byte[] key, Ptr from, int start, int end) {
		var to = 0L;
		var pos = 0;
		end = (end <= 0 || end <= start) ? key.length : end;
		var span = end - start;
		var array = this.array;
		// hoist in local, then perform a single heap write post-loop
		var v = from.v;

		while (pos < span) {
			to = u64(array.base(v) ^ u32(key[start + pos]));
			if (array.check(to) != i32(v)) {
				return OptionalInt.empty();
			}

			v = to;
			pos++;
		}

		var b = array.base(from.v = v);
		var check = array.check(b);
		if (check != i32(v)) {
			return CEDAR_NO_VALUE_OP;
		} else {
			return OptionalInt.of(array.base(b));
		}
	}

	private int find_place() {
		if (blocks_head_closed != 0) {
			return blocks.head(blocks_head_closed);
		}

		if (blocks_head_open != 0) {
			return blocks.head(blocks_head_open);
		}

		// the block is not enough, resize it and allocate it.
		return add_block() << 8;
	}

	private int find_places(byte[] child) {
		var idx = blocks_head_open;

		// we still have available 'Open' blocks.
		if (idx != 0) {
			assert (blocks.num(idx) > 1);
			var bz = blocks.prev(blocks_head_open);
			var nc = (short) child.length;

			for (;;) {
				// only proceed if the free slots are more than the number of children. Also, we
				// save the minimal number of attempts to fail in the `reject`, it only worths to
				// try out this block if the number of children is less than that number.
				if (blocks.num(idx) >= nc && nc < blocks.reject(idx)) {
					var e = blocks.head(idx);
					for (;;) {
						var base = e ^ u32(child[0]);

						var i = 1;
						// iterate through the children to see if they are available: (check < 0)
						while (array.check(base ^ u32(child[i])) < 0) {
							if (i == child.length - 1) {
								// we have found the available block.
								blocks.head(idx, e);
								return e;
							}
							i++;
						}

						// we save the next free block's information in `check`
						e = -array.check(e);
						if (e == blocks.head(idx)) {
							break;
						}
					}
				}

				// we broke out of the loop, that means we failed. We save the information in
				// `reject` for future pruning.
				blocks.reject(idx, nc);
				if (blocks.reject(idx) < reject.at(blocks.num(idx))) {
					// put this stats into the global array of information as well.
					reject.set(blocks.num(idx), blocks.reject(idx));
				}

				var idx_ = blocks.next(idx);

				blocks.trial(idx, blocks.trial(idx) + 1);

				// move this block to the 'Closed' block list since it has reached the max_trial
				if (blocks.trial(idx) == max_trial) {
					transfer_block(idx, BLOCK_TYPE_OPEN, BLOCK_TYPE_CLOSED, blocks_head_closed == 0);
				}

				// we have finsihed one round of this cyclic doubly-linked-list.
				if (idx == bz) {
					break;
				}

				// going to the next in this linked list group
				idx = idx_;
			}
		}

		return add_block() << 8;
	}

	private int follow(long from, byte label) {
		var base = array.base(from);

		var to = 0;
		var ul = u32(label);
		// the node is not there
		if (base < 0 || array.check(base ^ ul) < 0) {
			// allocate a e node
			to = pop_e_node(base, label, (int) from);
			var branch = to ^ ul;

			// maintain the info in ninfo
			push_sibling(from, branch, label, base >= 0);
		} else {
			// the node is already there and the ownership is not `from`, therefore a conflict.
			to = base ^ ul;
			if (array.check(to) != from) {
				// call `resolve` to relocate.
				to = resolve(from, base, label);
			}
		}

		return to;
	}

	public Match get(byte[] utf8) {
		var from = new Ptr();

		var r = find(utf8, from).orElse(CEDAR_NO_VALUE);

		if (r == CEDAR_NO_VALUE) {
			return null;
		} else {
			return new Match(r, utf8.length, from.v);
		}
	}

	public Match get(String str) {
		return get(utf8(str));
	}

	int get_head(int type) {
		return switch (type) {
		case BLOCK_TYPE_OPEN -> blocks_head_open;
		case BLOCK_TYPE_CLOSED -> blocks_head_closed;
		case BLOCK_TYPE_FULL -> blocks_head_full;
		default -> throw new Error("Invalid BlockType: " + type);
		};
	}

	public Stream<String> keys() {
		return predict("").map(this::suffix);
	}

	void next(long from, long p, long root, Scratch scratch) {
		var c = infos.sibling(array.base(from));

		// traversing up until there is a sibling or it has reached the root.
		while (c == 0 && from != root) {
			c = infos.sibling(from);
			from = u64(array.check(from));
			p--;
		}

		if (c != 0) {
			// it has a sibling so we leverage on `begin` to traverse the subtree down again.
			from = u64(array.base(from) ^ u32(c));
			begin(from, p + 1, scratch);
		} else {
			// no more work since we couldn't find anything.
			scratch.value(OptionalInt.empty());
			scratch.from(from);
			scratch.p(p);
		}
	}

	private void pop_block(int idx, int from, boolean last) {
		int head;
		if (last) {
			head = 0;
		} else {
			head = get_head(from);
//			var b = blocks.at(idx);
//			blocks.next(b.prev, b.next);
//			blocks.prev(b.next, b.prev);
			var next = blocks.next(idx);
			var prev = blocks.prev(idx);
			blocks.next(prev, next);
			blocks.prev(next, prev);

			if (idx == head) {
				// head = b.next;
				head = next;
			}
		}

		set_head(from, head);
	}

	private int pop_e_node(int base, byte label, int from) {
		int e;
		if (base < 0) {
			e = find_place();
		} else {
			e = base ^ u32(label);
		}

		var idx = e >> 8;
		// avoid alloc
		// var n = array.at(e);
		var nbase = array.base(e);
		var ncheck = array.check(e);

		blocks.incrementNum(idx, -1);
		// move the block at idx to the correct linked-list depending the free slots it still have.
		if (blocks.num(idx) == 0) {
			if (idx != 0) {
				transfer_block(idx, BLOCK_TYPE_CLOSED, BLOCK_TYPE_FULL, blocks_head_full == 0);
			}
		} else {
//			array.check(-n.base, n.check);
//			array.base(-n.check, n.base);
			array.check(-nbase, ncheck);
			array.base(-ncheck, nbase);

			if (e == blocks.head(idx)) {
				// blocks.head(idx, -n.check);
				blocks.head(idx, -ncheck);
			}

			if (idx != 0 && blocks.num(idx) == 1 && blocks.trial(idx) != max_trial) {
				transfer_block(idx, BLOCK_TYPE_OPEN, BLOCK_TYPE_CLOSED, blocks_head_closed == 0);
			}
		}

		if (label != 0) {
			array.base(e, -1);
		} else {
			array.base(e, 0);
		}
		array.check(e, from);
		if (base < 0) {
			array.base(from, e ^ u32(label));
		}
		return e;
	}

	void pop_sibling(int from, int base, byte label) {
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

	public Stream<Match> predict(byte[] utf8) {
		return new PrefixPredictIter(utf8).stream();
	}

	public Stream<Match> predict(String key) {
		return predict(utf8(key));
	}

	private void push_block(int idx, int to, boolean empty) {
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

	private void push_e_node(int e) {
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

	private void push_sibling(long from, int base, byte label, boolean hasChild) {
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
			while (true) {
				c = infos.sibling(c_ix = u64(base ^ u32(c)));
				if (!(ordered && (c != 0) && (c < label))) {
					break;
				}
			}
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

	private int resolve(long from_n, int base_n, byte label_n) {
		var to_pn = base_n ^ u32(label_n);

		// the `base` and `from` for the conflicting one.
		var from_p = array.check(to_pn);
		var base_p = array.base(from_p);

		// whether to replace siblings of newly added
		var flag = consult(base_n, base_p, infos.child(from_n), infos.child(from_p));

		// collect the list of children for the block that we are going to relocate.
		var children = flag ? set_child(base_n, infos.child(from_n), label_n, true) : set_child(base_p, infos.child(from_p), (byte) 0xFF, false);

		// decide which algorithm to allocate free block depending on the number of children we
		// have.
		var base = children.length == 1 ? find_place() : find_places(children);

		base ^= u32(children[0]);

		int from, base_;

		if (flag) {
			from = (int) from_n;
			base_ = base_n;
		} else {
			from = from_p;
			base_ = base_p;
		}

		if (flag && children[0] == label_n) {
			infos.child(from, label_n);
		}

		array.base(from, base);

		// the actual work for relocating the chilren
		for (var i = 0; i < children.length; i++) {
			var to = pop_e_node(base, children[i], from);
			var to_ = base_ ^ u32(children[i]);

			if (i == children.length - 1) {
				infos.sibling(to, (byte) 0);
			} else {
				infos.sibling(to, children[i + 1]);
			}

			if (flag && to_ == to_pn) {
				continue;
			}

			array.base(to, array.base(to_));

			var condition = array.base(to) > 0 && children[i] != 0;

			if (condition) {
				var c = infos.child(to_);

				infos.child(to, c);

				for (;;) {
					var idx = u64(array.base(to) ^ u32(c));
					array.check(idx, to);
					c = infos.sibling(idx);

					if (c == 0) {
						break;
					}
				}
			}

			if (!flag && to_ == (int) from_n) {
				from_n = u64(to);
			}

			// clean up the space that was moved away from.
			if (!flag && to_ == to_pn) {
				push_sibling(from_n, to_pn ^ u32(label_n), label_n, true);
				infos.child(to_, (byte) 0);

				if (label_n != 0) {
					array.base(to_, -1);
				} else {
					array.base(to_, 0);
				}

				array.check(to_, (int) from_n);
			} else {
				push_e_node(to_);
			}
		}

		// return the position that is free now.
		if (flag) {
			return base ^ u32(label_n);
		} else {
			return to_pn;
		}
	}

	public Stream<TextMatch> scan(byte[] utf8) {
		return new ScanItr(utf8, 0).stream();
	}

	public Stream<TextMatch> scan(String text) {
		return scan(utf8(text));
	}

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
		try (var ms = MemorySegment.mapFile(dst, 0, byteSize(), MapMode.READ_WRITE)) {
			serialize(ms);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	byte[] set_child(int base, byte c, byte label, boolean not_terminal) {
		var child = new byte[32];
		var pos = 0;

		if (c == 0) {
			if (pos == child.length) {
				child = Arrays.copyOf(child, pos + 16);
			}
			child[pos++] = c;
			c = infos.sibling(base ^ u32(c));
		}

		if (ordered) {
			while (c != 0 && u32(c) <= u32(label)) {
				if (pos == child.length) {
					child = Arrays.copyOf(child, pos + 16);
				}
				child[pos++] = c;
				c = infos.sibling(base ^ u32(c));
			}
		}

		if (not_terminal) {
			child[pos++] = label;
		}

		while (c != 0) {
			if (pos == child.length) {
				child = Arrays.copyOf(child, pos + 16);
			}
			child[pos++] = c;
			c = infos.sibling(base ^ u32(c));
		}

		return child.length == pos ? child : Arrays.copyOf(child, pos);
	}

	private void set_head(int type, int head) {
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

	public String suffix(long to, int len) {
		return suffix(to, len, new byte[len]);
	}

	public String suffix(long to, int len, byte[] scratch) {
		var s = suffixBytes(to, len, scratch);
		return new String(s, 0, len, UTF8);
	}

	public String suffix(Match m) {
		return suffix(m.from(), m.length());
	}

	public byte[] suffixBytes(long to, int len) {
		return suffixBytes(to, len, new byte[len]);
	}

	public byte[] suffixBytes(long to, int len, byte[] scratch) {
		scratch = scratch == null || scratch.length < len ? new byte[len] : scratch;
		var array = this.array;
		while (len-- > 0) {
			var from = u64(array.check(to));
			scratch[len] = (byte) ((array.base(from) ^ to) & 0xFF);
			to = from;
		}

		return scratch;
	}

	private void transfer_block(int idx, int from, int to, boolean toBlockEmpty) {
		var isLast = idx == blocks.next(idx);
		var isEmpty = toBlockEmpty && blocks.num(idx) != 0;

		pop_block(idx, from, isLast);
		push_block(idx, to, isEmpty);
	}

	public void update(byte[] utf8, int value) {
		update(utf8, value, 0, 0);
	}

	private int update(byte[] key, int value, long from, int pos) {
		if (from == 0 && key.length == 0) {
			throw new UnsupportedOperationException("Empty key");
		}

		while (pos < key.length) {
			from = follow(from, key[pos]);
			pos++;
		}

		var to = follow(from, (byte) 0);

		return array.getAndSetBase(to, value);
	}

	public void update(String key, int value) {
		update(utf8(key), value, 0, 0);
	}

	public IntStream values() {
		return predict("").mapToInt(Match::value);
	}

	public Stream<Match> withCommonPrefix(byte[] utf8) {
		return new PrefixIter(utf8).stream();
	}

	public Stream<Match> withCommonPrefix(String key) {

		return withCommonPrefix(utf8(key));
	}
}