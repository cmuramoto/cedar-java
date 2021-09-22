package com.nc.cedar;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

abstract class Itr<T> implements Iterator<T> {

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