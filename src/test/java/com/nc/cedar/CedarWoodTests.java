package com.nc.cedar;

import static com.nc.cedar.CedarTestSupport.randomAlpha;
import static com.nc.cedar.CedarTestSupport.toMap;
import static com.nc.cedar.CedarTestSupport.tuples;
import static com.nc.cedar.CedarTestSupport.values;
import static com.nc.cedar.CedarTestSupport.vec;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Similar suite based on Rust's cedarwood
 *
 * @author cmuramoto
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CedarWoodTests {

	@Test
	public void test_common_prefix_iter() {
		var dict = vec("a", "ab", "abc", "アルゴリズム", "データ", "構造", "网", "网球", "网球拍", "中", "中华", "中华人民", "中华人民共和国");

		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		var result = values(cedar.common_prefix_iter("abcdefg"));
		assertArrayEquals(vec(0, 1, 2), result);

		result = values(cedar.common_prefix_iter("网球拍卖会"));
		assertArrayEquals(vec(6, 7, 8), result);

		result = values(cedar.common_prefix_iter("中华人民共和国"));
		assertArrayEquals(vec(9, 10, 11, 12), result);

		result = values(cedar.common_prefix_iter("データ構造とアルゴリズム"));
		assertArrayEquals(vec(4), result);

	}

	@Test
	public void test_common_prefix_predict() {
		var dict = vec("a", "ab", "abc", "abcdef");
		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		var result = cedar.predict("a").mapToInt(Match::value).toArray();
		assertArrayEquals(vec(0, 1, 2, 3), result);

		result = cedar.predict("a").mapToInt(Match::length).toArray();
		assertArrayEquals(vec(0, 1, 2, 5), result);
	}

	@Test
	public void test_common_prefix_search() {
		var dict = vec("a", //
				"ab", //
				"abc", //
				"アルゴリズム", //
				"データ", //
				"構造", //
				"网", //
				"网球", //
				"网球拍", //
				"中", //
				"中华", //
				"中华人民", //
				"中华人民共和国");
		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		var result = cedar.withCommonPrefix("abcdefg").mapToInt(Match::value).toArray();

		assertArrayEquals(vec(0, 1, 2), result);

		result = cedar.withCommonPrefix("网球拍卖会").mapToInt(Match::value).toArray();
		assertArrayEquals(vec(6, 7, 8), result);

		result = cedar.withCommonPrefix("中华人民共和国").mapToInt(Match::value).toArray();
		assertArrayEquals(vec(9, 10, 11, 12), result);

		result = cedar.withCommonPrefix("データ構造とアルゴリズム").mapToInt(Match::value).toArray();

		assertArrayEquals(vec(4), result);
	}

	@Test
	public void test_duplication() {
		var dict = vec("些许端", "些須", "些须", "亜", "亝", "亞", "亞", "亞丁", "亞丁港");
		var key_values = tuples(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		assertEquals(6, cedar.get("亞").value());
		assertEquals(8, cedar.get("亞丁港").value());
		assertEquals(4, cedar.get("亝").value());
		assertEquals(1, cedar.get("些須").value());
	}

	@Test
	public void test_erase() {
		var dict = vec("a", "ab", "abc");
		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		assertEquals(0, cedar.get("a").value());
		assertEquals(1, cedar.get("ab").value());
		assertEquals(2, cedar.get("abc").value());

		cedar.erase("abc");
		assertEquals(0, cedar.get("a").value());
		assertEquals(1, cedar.get("ab").value());
		assertNull(cedar.get("abc"));

		cedar.erase("ab");
		assertEquals(0, cedar.get("a").value());
		assertNull(cedar.get("ab"));
		assertNull(cedar.get("abc"));

		cedar.erase("a");
		assertNull(cedar.get("a"));
		assertNull(cedar.get("ab"));
		assertNull(cedar.get("abc"));
	}

	@Test
	public void test_exact_match_search() {
		var dict = vec("a", "ab", "abc");
		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		var result = cedar.get("abc");
		assertEquals(2, result.value());
	}

	@Test
	public void test_insert_and_delete() {
		var dict = vec("a");
		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		var result = cedar.get("a");
		assertEquals(0, result.value());

		result = cedar.get("ab");
		assertNull(result);

		cedar.update("ab", 1);
		result = cedar.get("ab");
		assertEquals(1, result.value());

		cedar.erase("ab");
		result = cedar.get("ab");
		assertNull(result);

		cedar.update("abc", 2);
		result = cedar.get("abc");
		assertEquals(2, result.value());

		cedar.erase("abc");
		result = cedar.get("abc");
		assertNull(result);

		result = cedar.get("a");
		assertEquals(0, result.value());
	}

	@Test
	public void test_mass_erase() {
		var rng = ThreadLocalRandom.current();
		var max = 1000;
		var dict = new ArrayList<String>(1000);
		for (var i = 0; i < max; i++) {
			var chars = randomAlpha(rng, 30);

			dict.add(chars);
		}

		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		for (var i = 0; i < dict.size(); i++) {
			var s = dict.get(i);
			assertEquals(i, cedar.get(s).value());
			cedar.erase(s);
			assertNull(cedar.get(s));
		}
	}

	@Test
	public void test_quickcheck_like() {
		int max = 1000;
		var rng = ThreadLocalRandom.current();
		var dict = new ArrayList<String>(max);
		for (var i = 0; i < max; i++) {
			var chars = randomAlpha(rng, 30);

			dict.add(chars);
		}

		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		for (var i = 0; i < max; i++) {
			assertEquals(i, cedar.get(dict.get(i)).value());
		}
	}

	@Test
	public void test_quickcheck_like_with_deep_trie() {
		var rng = ThreadLocalRandom.current();
		var max = 1000;
		var dict = new ArrayList<String>(1000);
		var sb = new StringBuilder();
		for (var i = 0; i < max; i++) {
			var c = randomAlpha(rng);
			sb.append(c);
			dict.add(sb.toString());
		}

		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		for (var i = 0; i < max; i++) {
			var s = dict.get(i);
			assertEquals(i, cedar.get(s).value());
		}
	}

	@Test
	public void test_unicode_grapheme_cluster() {
		var dict = vec("a", "abc", "abcde\u0301");

		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		var result = cedar.withCommonPrefix("abcde\u0301\u1100\u1161\uAC00").mapToInt(Match::value).toArray();
		assertArrayEquals(vec(0, 1, 2), result);
	}

	@Test
	public void test_unicode_han_sip() {
		var dict = vec("讥䶯䶰", "讥䶯䶰䶱䶲", "讥䶯䶰䶱䶲䶳䶴䶵𦡦");

		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		var result = cedar.withCommonPrefix("讥䶯䶰䶱䶲䶳䶴䶵𦡦").mapToInt(Match::value).toArray();
		assertArrayEquals(vec(0, 1, 2), result);
	}

	@Test
	public void test_update() {
		var dict = vec("a", "ab", "abc");
		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);

		cedar.update("abcd", 3);

		assertEquals(0, cedar.get("a").value());
		assertEquals(1, cedar.get("ab").value());
		assertEquals(2, cedar.get("abc").value());
		assertEquals(3, cedar.get("abcd").value());
		assertNull(cedar.get("abcde"));

		dict = vec("a", "ab", "abc");
		key_values = toMap(dict);
		cedar = new Cedar();
		cedar.build(key_values);
		cedar.update("bachelor", 1);
		cedar.update("jar", 2);
		cedar.update("badge", 3);
		cedar.update("baby", 4);

		assertEquals(1, cedar.get("bachelor").value());
		assertEquals(2, cedar.get("jar").value());
		assertEquals(3, cedar.get("badge").value());
		assertEquals(4, cedar.get("baby").value());
		assertNull(cedar.get("abcde"));

		dict = vec("a", "ab", "abc");
		key_values = toMap(dict);
		cedar = new Cedar();
		cedar.build(key_values);

		cedar.update("中", 1);
		cedar.update("中华", 2);
		cedar.update("中华人民", 3);
		cedar.update("中华人民共和国", 4);

		assertEquals(1, cedar.get("中").value());
		assertEquals(2, cedar.get("中华").value());
		assertEquals(3, cedar.get("中华人民").value());
		assertEquals(4, cedar.get("中华人民共和国").value());
		assertNull(cedar.get("abcde"));
	}

}
