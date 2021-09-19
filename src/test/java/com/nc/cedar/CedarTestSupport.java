package com.nc.cedar;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface CedarTestSupport {

	static byte randomAlpha(ThreadLocalRandom rng) {
		int n = (rng.nextInt(12));

		return (byte) switch (n) {
		case 0, 1, 2, 3 -> rng.nextInt(48, 58);
		case 4, 5, 6, 7 -> rng.nextInt(65, 91);
		default -> rng.nextInt(97, 123);
		};
	}

	static String randomAlpha(ThreadLocalRandom rng, int len) {
		var chars = new byte[len];

		for (var i = 0; i < len; i++) {
			chars[i] = randomAlpha(rng);
		}

		return new String(chars);
	}

	static Map<String, Integer> toMap(Collection<String> c) {
		var rv = new LinkedHashMap<String, Integer>();
		for (var string : c) {
			rv.put(string, rv.size());
		}

		return rv;
	}

	static Map<String, Integer> toMap(String[] vals) {
		return toMap(Arrays.asList(vals));
	}

	static List<Map.Entry<String, Integer>> tuples(String[] vals) {
		var v = 0;
		var rv = new ArrayList<Map.Entry<String, Integer>>();
		for (var s : vals) {
			rv.add(new AbstractMap.SimpleEntry<>(s, v++));
		}

		return rv;
	}

	static int[] values(Iterator<Match> itr) {
		return values(StreamSupport.stream(Spliterators.spliteratorUnknownSize(itr, Spliterator.NONNULL), false));
	}

	static int[] values(Stream<Match> stream) {
		return stream.mapToInt(Match::value).toArray();
	}

	static int[] vec(int... values) {
		return values;
	}

	static String[] vec(String... keys) {
		return keys;
	}

}
