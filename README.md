# cedar-java

A Java backport of Rust's [cedarwood](https://github.com/MnO2/cedarwood), an efficiently-updatable double-array trie, with some additions from the original [cedar](http://www.tkl.iis.u-tokyo.ac.jp/~ynaga/cedar/) and (de-)serialization support.

This trie works like a SortedMap<String,int> and it's lookups run in O(k), where k is the length of the key.

The implementation uses preview features (records) and its underlying data structures are based on (native) MemorySegments of the incubator *foreign-api*, but can easilly be ported to a ByteBuffer based implementation. 

By using MemorySegments to represent the trie's arrays we achieve memory density (and bypass pointer dereferences) which is currently impossible with vanilla java OOP and won't be until valhalla goes GA.

Another advantage of representing data off-heap is that the trie can be trivially be loaded/stored with Memory Mapping, which translates to millisecond persistence even for very large tries.

### Usage

This library requires no additional dependencies, but requires some **jvm args** in order to work:


```none
--enable-preview --add-modules jdk.incubator.foreign --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
```

It won't work with any jvm version other than 16, either due to missing apis (older vms) or due to foreing-api changes/class loading restriction with classes compiled with preview features (jdk 17). Backport should be straightforward: MemorySegment->ByteBuffer, records->final immutable classes. 

#### Storing values

```java

var cedar = new Cedar();
cedar.update("some_key", 0);

var array = new String[]{"one", "two", "three"};

/*
 * Bulk update, values will be incremented according to array index.
 * This is a convenience method. Performance is the same as iterating
 * over the array and calling update for each key.
 */ 
cedar.build(array);

// var-args bulk update version
cedar.build("four", "five", "six");

// bulk update by pairs of key/values
var map = Map.of("foo", 17, "bar", 22);
cedar.build(map);

// bulk update by 'tuples'
var map = List.of(new AbstractMap.SimpleEntry<>("roo", 12), new AbstractMap.SimpleEntry<>("baz", 26));
cedar.build(map);

```

#### Retrieving values

Value retrieval is slightly distinct from rust's version and some additional methods for streaming and suffix construction are provided.

```java
var cedar = new Cedar();
cedar.update("foo", 0);

// same as rust's exact_match_search
Match m = cedar.get("foo"); // { value: 0, length: 3, from: 0}
```

The **from** value from the match structure is a *pointer* to the internal trie structure that can be used to rebuild suffixes. In case of exact matches, the suffix is the key itself.

This library can be used as a replacement of [AhoCorasickDoubleArrayTrie](https://github.com/hankcs/AhoCorasickDoubleArrayTrie) for finding all matches in a given text:

```java 
var cedar = new Cedar();
// ---------012345678910
var text = "foo foo bar";

cedar.update("fo", 0);
cedar.update("foo", 1);
cedar.update("ba", 2);
cedar.update("bar", 3);

//TextMatch returns the end offset (not the length) like exact/prefix match searches
List<TextMatch> matches = cedar.scan(text).toList();
//{begin: 0, end: 2, value: 0} -> fo
//{begin: 0, end: 3, value: 1} -> foo
//{begin: 4, end: 6, value: 0} -> fo
//{begin: 4, end: 7, value: 1} -> foo 
//{begin: 8, end: 10, value: 2} -> ba
//{begin: 8, end: 11, value: 3} -> bar
```

#### Finding by prefix and completing corresponding suffixes

```java
var cedar = new Cedar();
cedar.build("banana", "barata", "bacanal", "bacalhau", "mustnotmatch_ba");

var prefix = "ba";

var matched = cedar.predict(prefix).mapToInt(match -> {
	var found = values[match.value()];

        // Completes a suffix of corresponding length, by starting at cursor from.
        // Based on original C cedar
	var suffix = cedar.suffix(match.from(), match.length());

	assertEquals(prefix.length() + suffix.length(), found.length());

	assertEquals(prefix + suffix, found);

	return match.value();
}).distinct().count();

assertEquals(values.length - 1, matched);
```

#### Streaming

An empty prefix can be used to stream all entries of the trie:

```java

var universe = cedar.predict("");

universe.forEach(match-> {
  var key = cedar.suffix(match);
  var value = match.value();
  ...
});
```

Keys and values can be fetched via:

```java

// This will trigger allocations, since data is off-heap!
Stream<String> keys = cedar.keys();

IntStream values = cedar.values();
```

#### Serialization

Cedar trie basically encapsulates 4 flat off-heap arrays, which translates to trivial copy operations:

```java
var cedar = new Cedar();

cedar.buid("key1", "key2");

var tmp = Files.createTempFile("cedar", "bin");

cedar.serialize(tmp);
cedar.close(); // frees memory

// deserialization with no copy. If data resides in OS page-cache this is pratically a no-op
cedar = Cedar.deserialize(tmp, false);

/**
 * If update triggers a resize, the internal buffer will grow but won't be mmaped anymore. 
 * Currently there's no auto-sync support, the trie has to be serialized again.
 */
cedar.update("foo", 26);

// deserialization with copy. File is mmaped, data is copied to internal buffers and then the mapping released.
cedar = Cedar.deserialize(tmp, true);
```


### Caveats

The trie expects strings to be **UTF-8** encoded. Since Java strings are encoded with either **Latin1**(~ascii) or **UTF-16**, and UTF-8 is 1-1 for characters in ascii domain, we can bypass encoding overhead by inspecting the String's *coder* value. If 0 (Latin1), we fetch the array via reflection (Unsafe for better speed), otherwise we convert to UTF-8 and create a new array.

If working with **UTF-8** entries, the offsets repported are based on the array obtained from *s.getBytes(UTF8)*. If offsets matter, it's better to work with pre encoded keys directly:

```java
var cs = Charset.forName("UTF-8");
var key = "中华人民共和国";
var key_utf8 = key.getBytes(cs);

var cedar = new Cedar();
cedar.update(key_utf8,0);

cedar.get(key_utf8);
```

Memory allocated by Cedar starts with 256x8=2048 bytes for its backing "array" and every time it needs to reallocate it doubles the required capacity. For small tries this is not an issue, however when it becomes huge the amount

Consider the following example for zero padded numbers (9 bytes each):

```java
static final int MAX = 100_000_000;

static String str(int v) {
  return String.format("%09d", v);
}

void testHugeCedar() {
  IntStream.range(0, MAX).sorted().forEach(v -> {
    cedar.update(str(v), v);
  });
}
```

When **v=63576696**, the structures will double in size, the backing array with 1GB will grow to 2GB, which means it will reserve enough space to store keys up to **v=127153513** in order to insert a single key, which may cause allocation stalls. So if your dataset has about 70-80 million keys a best fit strategy might be using two cedars, one containing the max numbers of keys before a resize (63576695) and the other the rest.

As of now, there's no support for memory reallocation, meaning, in order to grow from 1GB to 2GB, first we allocate a 2GB chunk, copy the 1GB into it and then release the buffer. This may trigger OOME or swapping when the structure grows very large.

### Performance

As stated, lookups run in O(k), regardless the size of the trie. In the example above, lookups reach peak performance of about 9 million (ZGC) to 10 million (ParallelGC/G1GC) queries/second on (a core i7-10750H 2.6GHz), for tries with 10-100 million keys.
