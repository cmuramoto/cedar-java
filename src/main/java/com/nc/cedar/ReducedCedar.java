package com.nc.cedar;

import static com.nc.cedar.Bits.UTF8;
import static com.nc.cedar.Bits.i32;
import static com.nc.cedar.Bits.u32;
import static com.nc.cedar.Bits.u64;
import static com.nc.cedar.Bits.utf8;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jdk.incubator.foreign.MemorySegment;

public final class ReducedCedar extends BaseCedar {

	final class PrefixIter extends Itr<Match> {

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

				if (value != ABSENT) {
					if (value == NO_VALUE) {
						i++;
						continue;
					} else {
						curr = new Match((int) value, i, from.v);
						i++;
						break;
					}
				} else {
					break;
				}
			}
		}
	}

	final class PrefixPredictIter extends Itr<Match> implements Scratch {

		final byte[] key;
		final Ptr from;
		long p;
		long root;
		long value;

		PrefixPredictIter(byte[] key) {
			this.key = key;
			from = new Ptr();
		}

		@Override
		void advance() {
			if (value == ABSENT) {
				return;
			}

			if (from.v == 0 && p == 0) {
				// To locate the prefix's position first, if it doesn't exist then that means we
				// don't have do anything. `from` would serve as the cursor.

				if (key.length == 0 || find(key, from) != ABSENT) {
					this.root = this.from.v;

					begin(this.from.v, this.p, this);

					tryAdvance();
				}
			} else {
				tryAdvance();
			}
		}

		@Override
		public void set(long from, long p, long value) {
			this.from.v = from;
			this.p = p;
			this.value = value;
		}

		void tryAdvance() {
			if (value != ABSENT) {
				var result = new Match((int) value, (int) p, from.v);

				ReducedCedar.this.next(from.v, p, root, this);

				curr = result;
			} else {
				curr = null;
			}
		}
	}

	final class ScanItr extends Itr<TextMatch> {

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

					if (r != ABSENT) {
						if (r == NO_VALUE) {
							i++;
							continue;
						} else {
							curr = new TextMatch(base, base + i + 1, (int) r);
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

	public static ReducedCedar deserialize(MemorySegment src, boolean copy) {
		return BaseCedar.deserialize(ReducedCedar::new, src, copy);
	}

	public static ReducedCedar deserialize(Path src, boolean copy) {
		return BaseCedar.deserialize(ReducedCedar::new, src, copy);
	}

	public ReducedCedar() {
		this(true);
	}

	public ReducedCedar(boolean ordered) {
		this(Nodes.initial_r(), NodeInfos.initial(), Blocks.initial(), Rejects.initial(), ordered);

		capacity = 256;
		size = 256;
		ordered = true;
		max_trial = 1;
	}

	private ReducedCedar(Nodes array, NodeInfos infos, Blocks blocks, Rejects reject, boolean ordered) {
		super(array, infos, blocks, reject, ordered);
	}

	private void begin(long from, long p, Scratch s) {
		var array = this.array;
		var infos = this.infos;
		var base = array.base_r(from);

		var c = infos.child(from);

		if (from == 0) {
			c = infos.sibling(base ^ u32(c));

			// if no sibling couldn be found from the virtual root, then we are done.
			if (c == 0) {
				s.set(from, p, ABSENT);
				return;
			}
		}

		// recursively traversing down to look for the first leaf.
		while (c != 0) {
			from = u64(array.base_r(from) ^ u32(c));
			c = infos.child(from);
			p += 1;
		}

		// reduced-trie
		if (array.base(from) >= 0) {
			s.set(from, p, array.base(from));
			return;
		}

		// To return the value of the leaf.
		var v = array.base(array.base_r(from) ^ u32(c));
		s.set(from, p, v);
	}

	public void build(byte[]... keys) {
		for (var i = 0; i < keys.length; i++) {
			update(keys[i], i);
		}
	}

	@Override
	public <E extends Map.Entry<String, Integer>> void build(Iterable<E> kv) {
		for (var e : kv) {
			update(e.getKey(), e.getValue());
		}
	}

	@Override
	public void build(Map<String, Integer> kv) {
		for (var e : kv.entrySet()) {
			update(e.getKey(), e.getValue());
		}
	}

	@Override
	public void build(String... keys) {
		for (var i = 0; i < keys.length; i++) {
			update(keys[i], i);
		}
	}

	Iterator<Match> common_prefix_iter(byte[] utf8) {
		return new PrefixIter(utf8);
	}

	@Override
	Iterator<Match> common_prefix_iter(String key) {
		return common_prefix_iter(utf8(key));
	}

	private long erase(byte[] key) {
		var from = new Ptr();
		var r = find(key, from);

		if ((r & ABSENT_OR_NO_VALUE) == 0) {
			erase(from.v);
		}

		return r;
	}

	private void erase(long from) {
		// reduced-trie
		var e = array.base(from) >= 0 ? i32(from) : array.base_r(from);

		// reduced-trie
		from = u64(array.check(e));

		boolean has_sibling;
		do {
			var base = array.base_r(from);
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

	@Override
	public long erase(String key) {
		return erase(utf8(key));
	}

	long find(byte[] key) {
		var from = 0L;
		var to = 0L;
		var pos = 0;
		var array = this.array;
		// hoist in local, then perform a single heap write post-loop
		while (pos < key.length) {
			// reduced-trie
			if (array.base(from) >= 0) {
				break;
			}

			to = u64(array.base_r(from) ^ u32(key[pos]));
			if (array.check(to) != i32(from)) {
				return ABSENT;
			}

			from = to;
			pos++;
		}

		// reduced-trie
		if (array.base(from) >= 0) {
			if (pos == key.length) {
				return array.base(from);
			} else {
				return ABSENT;
			}
		}

		var b = array.base_r(from);
		var check = array.check(b);
		if (check != i32(from)) {
			return NO_VALUE;
		} else {
			return array.base(b);
		}
	}

	long find(byte[] key, Ptr from) {
		return find(key, from, 0, key.length);
	}

	long find(byte[] key, Ptr from, int start, int end) {
		var to = 0L;
		var pos = 0;
		end = (end <= 0 || end <= start) ? key.length : end;
		var span = end - start;
		var array = this.array;
		// hoist in local, then perform a single heap write post-loop
		var v = from.v;

		while (pos < span) {
			// reduced-trie
			if (array.base(v) >= 0) {
				break;
			}

			to = u64(array.base_r(v) ^ u32(key[start + pos]));
			if (array.check(to) != i32(v)) {
				from.v = v;
				return ABSENT;
			}

			v = to;
			pos++;
		}

		from.v = v;

		// reduced-trie
		if (array.base(v) >= 0) {
			if (pos == end) {
				return array.base(v);
			} else {
				return ABSENT;
			}
		}

		var b = array.base_r(v);
		var check = array.check(b);
		if (check != i32(v)) {
			return NO_VALUE;
		} else {
			return array.base(b);
		}
	}

	@Override
	public long find(String s) {
		return find(utf8(s));
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
					do {
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

					} while ((e != blocks.head(idx)));
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
		var base = array.base_r(from);

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

		var r = find(utf8, from);

		if ((r & ABSENT_OR_NO_VALUE) != 0) {
			return null;
		} else {
			return new Match((int) r, utf8.length, from.v);
		}
	}

	@Override
	public Match get(String str) {
		return get(utf8(str));
	}

	public Stream<String> keys() {
		return predict("").map(this::suffix);
	}

	void next(long from, long p, long root, Scratch scratch) {
		var c = (byte) 0;

		// reduced-trie
		if (array.base(from) < 0) {
			c = infos.sibling(array.base_r(from));
		}

		// traversing up until there is a sibling or it has reached the root.
		while (c == 0 && from != root) {
			c = infos.sibling(from);
			from = u64(array.check(from));
			p--;
		}

		if (c != 0) {
			// it has a sibling so we leverage on `begin` to traverse the subtree down again.
			from = u64(array.base_r(from) ^ u32(c));
			begin(from, p + 1, scratch);
		} else {
			// no more work since we couldn't find anything.
			scratch.set(from, p, ABSENT);
		}
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

		// reduced-trie
		array.base(e, VALUE_LIMIT);
		array.check(e, from);
		if (base < 0) {
			array.base(from, -(e ^ i32(label)) - 1);
		}

		return e;
	}

	public Stream<Match> predict(byte[] utf8) {
		return new PrefixPredictIter(utf8).stream();
	}

	@Override
	public Stream<Match> predict(String key) {
		return predict(utf8(key));
	}

	private int resolve(long from_n, int base_n, byte label_n) {
		var to_pn = base_n ^ u32(label_n);

		// the `base` and `from` for the conflicting one.
		var from_p = array.check(to_pn);
		var base_p = array.base_r(from_p);

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

		// reduced-trie
		array.base(from, -base - 1);

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

			// reduced-trie
			var condition = array.base(to) < 0 && children[i] != 0;

			if (condition) {
				var c = infos.child(to_);

				infos.child(to, c);

				do {
					var idx = u64(array.base_r(to) ^ u32(c));
					array.check(idx, to);
					c = infos.sibling(idx);
				} while (c != 0);
			}

			if (!flag && to_ == (int) from_n) {
				from_n = u64(to);
			}

			// clean up the space that was moved away from.
			if (!flag && to_ == to_pn) {
				push_sibling(from_n, to_pn ^ u32(label_n), label_n, true);
				infos.child(to_, (byte) 0);

				// reduced-trie
				array.base(to_, VALUE_LIMIT);

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

	@Override
	public Stream<TextMatch> scan(String text) {
		return scan(utf8(text));
	}

	byte[] set_child(int base, byte c, byte label, boolean not_terminal) {
		var child = new byte[32];
		var pos = 0;

		if (c == 0) {
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
			if (pos == child.length) {
				child = Arrays.copyOf(child, pos + 16);
			}
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

	@Override
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
			scratch[len] = (byte) ((array.base_r(from) ^ to) & 0xFF);
			to = from;
		}

		return scratch;
	}

	public void update(byte[] utf8, int value) {
		update(utf8, value, 0, 0);
	}

	private int update(byte[] key, int value, long from, int pos) {
		if (from == 0 && key.length == 0) {
			throw new UnsupportedOperationException("Empty key");
		}

		while (pos < key.length) {
			// reduced-trie
			var val_ = array.base(from);
			if (val_ >= 0 && val_ != VALUE_LIMIT) {
				var to = follow(from, (byte) 0);
				array.base(to, val_);
			}

			from = follow(from, key[pos]);
			pos++;
		}

		// reduced-trie
		var to = array.base(from) >= 0 ? i32(from) : follow(from, (byte) 0);

		// reduced-trie
		if (array.base(to) == VALUE_LIMIT) {
			array.base(to, 0);
		}

		return array.getAndSetBase(to, value);
	}

	@Override
	public void update(String key, int value) {
		update(utf8(key), value, 0, 0);
	}

	public IntStream values() {
		return predict("").mapToInt(Match::value);
	}

	public Stream<Match> withCommonPrefix(byte[] utf8) {
		return new PrefixIter(utf8).stream();
	}

	@Override
	public Stream<Match> withCommonPrefix(String key) {

		return withCommonPrefix(utf8(key));
	}
}