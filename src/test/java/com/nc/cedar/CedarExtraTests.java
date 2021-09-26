package com.nc.cedar;

import static com.nc.cedar.CedarTestSupport.toMap;
import static com.nc.cedar.CedarTestSupport.vec;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(Parameterized.class)
public class CedarExtraTests extends BaseCedarTests {

	static record ScanMatch(String sub, int begin, int end, int value) {

		ScanMatch(String text, TextMatch tm) {
			this(text.substring(tm.begin(), tm.end()), tm.begin(), tm.end(), tm.value());
		}

		void expect(String text, int begin, int end, int value) {
			assertEquals(sub, text);
			assertEquals(this.begin, begin);
			assertEquals(this.end, end);
			assertEquals(this.value, value);
		}
	}

	/**
	 * https://github.com/MnO2/cedarwood/issues/12
	 */
	@Test
	public void test_delete_prefix_wont_corrupt_trie() {
		var c = instantiate();

		c.update("AA", 0);
		c.update("AB", 1);

		Assert.assertTrue((BaseCedar.ABSENT_OR_NO_VALUE & c.erase("A")) != 0);
		Assert.assertTrue((BaseCedar.ABSENT_OR_NO_VALUE & c.erase("A")) != 0);

		Assert.assertEquals(0, c.find("AA"));
		Assert.assertEquals(1, c.find("AB"));

		Assert.assertEquals(0, c.erase("AA"));
		Assert.assertEquals(1, c.erase("AB"));

		Assert.assertTrue((BaseCedar.ABSENT_OR_NO_VALUE & c.erase("AA")) != 0);
		Assert.assertTrue((BaseCedar.ABSENT_OR_NO_VALUE & c.erase("AB")) != 0);

	}

	@Test
	public void test_empty_streams_whole_trie() {
		var values = vec("banana", "barata", "bacanal", "bacalhau", "mustnotmatch_ba");
		var m = toMap(values);
		var cedar = instantiate();
		cedar.build(m);

		var prefix = "";

		var matched = cedar.predict(prefix).mapToInt(match -> {
			var found = values[match.value()];

			var suffix = cedar.suffix(match.from(), match.length());

			assertEquals(suffix.length(), found.length());

			assertEquals(suffix, found);

			return match.value();
		}).distinct().count();

		assertEquals(values.length, matched);
	}

	@Test
	public void test_mass_erase_uuid() {
		var uuids = IntStream.range(0, 10000).mapToObj(__ -> UUID.randomUUID().toString()).distinct().toArray(String[]::new);

		var cedar = instantiate();

		cedar.build(uuids);

		for (var i = 0; i < uuids.length; i++) {
			var key = uuids[i];
			assertEquals(i, cedar.find(key));
			assertEquals(i, cedar.erase(key));
			assertTrue((BaseCedar.ABSENT_OR_NO_VALUE & cedar.find(key)) != 0);
		}

		for (var i = 0; i < uuids.length; i++) {
			var key = uuids[i];
			assertTrue((BaseCedar.ABSENT_OR_NO_VALUE & cedar.find(key)) != 0);
		}
	}

	@Test
	public void test_predict_two() {
		var cedar = instantiate();

		cedar.update("barata", 666);
		cedar.update("banana", 7);

		var matches = cedar.predict("ba").mapToInt(Match::value).toArray();

		// trie is ordered by default
		assertArrayEquals(vec(7, 666), matches);
	}

	@Test
	public void test_reconstruct_by_suffix() {
		var values = vec("banana", "barata", "bacanal", "bacalhau", "mustnotmatch_ba");
		var m = toMap(values);
		var cedar = instantiate();
		cedar.build(m);

		var prefix = "ba";

		var matched = cedar.predict(prefix).mapToInt(match -> {
			var found = values[match.value()];

			var suffix = cedar.suffix(match.from(), match.length());

			assertEquals(prefix.length() + suffix.length(), found.length());

			assertEquals(prefix + suffix, found);

			return match.value();
		}).distinct().count();

		assertEquals(values.length - 1, matched);
	}

	@Test
	public void test_scan() {
		var cedar = instantiate();
		// ---------012345678910
		var text = "foo foo bar";

		cedar.update("fo", 0);
		cedar.update("foo", 1);
		cedar.update("ba", 2);
		cedar.update("bar", 3);

		var matches = cedar.scan(text).map(tm -> new ScanMatch(text, tm)).toArray(ScanMatch[]::new);

		if (reduced) {
			// reduced scan only matches short prefixes :(
			assertEquals(3, matches.length);

			matches[0].expect("fo", 0, 2, 0);
			matches[1].expect("fo", 4, 6, 0);
			matches[2].expect("ba", 8, 10, 2);
		} else {
			assertEquals(6, matches.length);

			matches[0].expect("fo", 0, 2, 0);
			matches[1].expect("foo", 0, 3, 1);
			matches[2].expect("fo", 4, 6, 0);
			matches[3].expect("foo", 4, 7, 1);
			matches[4].expect("ba", 8, 10, 2);
			matches[5].expect("bar", 8, 11, 3);
		}
	}
}
