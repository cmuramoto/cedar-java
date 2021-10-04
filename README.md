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

// same as C's exactMatchSearch
Match m = cedar.match("foo"); // { value: 0, length: 3, from: 0}

// if only value is required, avoids allocation
long v = cedar.get("foo"); // 0
```

The result of **get** is a long value contains either the associated value with the key or masks: 

* NO_VALUE (1L<<32), which indicates that the key exists as prefix, but it's not a whole word
* ABSENT (1L<<33), which indicates that the prefix does not exist
* To get the value, test first with BaseCedar.isValue(v) and cast to int

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

### Footprint

Memory allocated by Cedar starts with 256x8=2048 bytes for its backing "array" and every time it needs to reallocate, by default it will demand twice the current capacity. For small tries this is not an issue, however when it becomes huge the amount of memory required to updated the trie with a small amount of new keys may become unwieldy.

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

When **v=63576696**, the structures will double in size, the backing array with 1GB will grow to 2GB, which means it will reserve enough space to store keys up to **v=127153513** in order to insert a single key, which may cause allocation stalls.

To cope with this, cedar can be instantiated with a reallocation cap: 

```java
var cedar = new Cedar(4*1024*1024);
```

If reallocation demands less than the cap (4MB), say 512 bytes, only 512 bytes will be used, otherwise up to 4MB will be used. This policy imposes a penalty for creating huge tries from scratch, but caps memory waste once it grows very large. For the [distinct](http://web.archive.org/web/20120206015921/http://www.naskitis.com/distinct_1.bz2 keys dataset) (~28 million keys with average length 9.58), default reallocation will demand 1290MB of memory, whereas using a 4MB policy will result in a trie demanding 1050MB.

Another option to reduce footprint is to use a **reduced** trie, which works only with ASCII. 

```java
var cedar = new ReducedCedar(); 
```
or

```java
var cedar = new ReducedCedar(4*1024*1024); 
```

In the same dataset, with standard reallocation policy, the reduced trie ends up using the same amount of memory, however it peaks at ~23.5 million keys and the standard trie peaks at ~18.8 million keys:

<p align="center">
<img src="https://user-images.githubusercontent.com/7014591/134908962-573cd910-77f8-4acb-a0d4-946d2ca5a90b.png"></img>
</p>

As can be seen, there's no memory payoff when using the reduced trie to load the entire dataset, but using a 4MB reallocation policy we end up with ~23.5% memory savings in comparision to the standard trie, with reduced trie peaking at 850MB (vs 1050MB):

<p align="center">
<img src="https://user-images.githubusercontent.com/7014591/134914565-5ff9561d-2352-4d31-a582-f35f12bbb65e.png"></img>
</p>

As of now, there's no true support for memory reallocation, meaning, in order to grow from 1024MB to 1028MB, first we allocate a 1028MB chunk, copy the 1GB into it and then release the buffer. This may trigger OOME or swapping when the structure grows very large.


### Performance

As stated, lookups run in O(k), regardless the size of the trie. Of course in practice, a small trie will perform better due to cache locality. In the example above, lookups reach peak performance of about 9 million (ZGC) to 10 million (ParallelGC/G1GC) queries/second on (a core i7-10750H 2.6GHz), for tries with 10-100 million keys.

Comparing with the original C [cedar](http://www.tkl.iis.u-tokyo.ac.jp/~ynaga/cedar/) implementation for the distinct and skew datasets we got:

| Dataset  | #keys| #distinct| C ns/read | Java(C2) ns/read | C ns/write | Java(C2) ns/write |
| --- | --- | --- | --- | --- | --- | --- |
| [distinct](http://web.archive.org/web/20120206015921/http://www.naskitis.com/distinct_1.bz2)  | 28.772.169 | 28.772.169| 233.98 | 377.92 | 626.38  | 665.05|
| [skew](http://web.archive.org/web/20120206015921/http://www.naskitis.com/skew1_1.bz2)  | 177.999.203 | 612.219 | 31.55 | 89.72| 53.23 | 37.47|

Java tests run with:

```none
-Xmx128m -XX:MaxDirectMemorySize=4G
```

Oddly enough java seems to perform better in skewed writes, probably due to some realloc jitter.

The measurement used was different and more granular from that employed in C code, with nano-second measurement for every operation

```java
long query;

long find(Cedar cedar, String key) {
  var utf8 = Bits.utf8(key); // won't alloc for ascii
  var now = System.nanoTime();
  var rv = cedar.find(utf8);
  query += (System.nanoTime() - now);
  return rv;
}
```

After further inspection of C benchmark code, it can be seen that it expects all query data to be in memory using positional lookups to dodge memcpy:

```C
char* data = 0;
const size_t size = read_data (queries, data);
// search
int n (0), n_ (0);
::gettimeofday (&st, NULL);
lookup (t, data, size, n_, n);
::gettimeofday (&et, NULL);
double elapsed = (et.tv_sec - st.tv_sec) + (et.tv_usec - st.tv_usec) * 1e-6;
std::fprintf (stderr, "%-20s %.2f sec (%.2f nsec per key)\n",
                  "Time to search:", elapsed, elapsed * 1e9 / n);
```
where

```C
void lookup (cedar_t* t, char* data, size_t size, int& n_, int& n) {
  for (char* start (data), *end (data), *tail (data + size);
       end != tail; start = ++end) {
    end = find_sep (end);
    if (lookup_key (t, start, end - start))
      ++n_;
    ++n;
  }
}

inline char* find_sep (char* p) { while (*p != '\n') ++p; *p = '\0'; return p; }

inline bool lookup_key (cedar_t* t, const char* key, size_t len)
{ return t->exactMatchSearch <int> (key, len) >= 0; }
```
which translates to Java as

```java
void benchLookup(Cedar cedar, byte[] data) {
  var start = 0;
  var lines = 0;
  var found = 0;
  var now = System.nanoTime();
  for (var i = 0; i < data.length; i++) {
    if (data[i] == '\n') {
      if ((cedar.find(data, start, i) & BaseCedar.ABSENT_OR_NO_VALUE) == 0) {
        found++;
      }
      lines++;
      start = i + 1;
    }
  }
  var dq = (double)System.nanoTime() - now;

  System.out.printf("lines: %d. found: %d. query time: %.2f. ns/q: %.2f\n", lines, found, dq, dq / lines);
}
```

In order to attempt to get closer to C++ performance, the lookup code used was:

```java
var data = Files.readAllBytes("...");
var mem = U.allocateMemory(data.length);
U.copyMemory(data, ARRAY_BYTE_BASE_OFFSET, null, mem, len);

for (var i = 0; i < 10; i++) {
  run(cedar, mem, len, c);
}
```

where:


```java
void run(Cedar cedar, long data, int len) {

  var start = 0;
  var lines = 0;
  var now = System.nanoTime();
  
  for (var i = 0; i < len; i++) {
    if (U.getByte(data + i) == '\n') {
      if ((cedar.get(data, start, i) & BaseCedar.ABSENT_OR_NO_VALUE) == 0) {
        lines++;
      }
      start = i + 1;
    }
  }
  var dq = (double) System.nanoTime() - now;

  System.out.printf("(read) lines: %d. query time: %.2f. ns/q: %.2f.\n", lines, dq, dq / lines);
}


long get(long base, int pos, int end) {
  var from = 0L;
  var to = 0L;
  var addr = this.array.address();

  while (pos < end) {
    to = U.getInt(addr + (from << 3)) ^ u32(U.getByte(base + pos));
    if (U.getInt(addr + (to << 3) + 4) != from) {
      return ABSENT;
    }
    from = to;
    pos++;
  }

  to = U.getLong(addr + (U.getInt(addr + (from << 3)) << 3));

  if ((to >>> 32) != from) {
    return NO_VALUE;
  }

  return to & 0xFFFFFFFFL;
}
```
, which mirrors:


```C
int da::find (const char* key, size_t& from, size_t& pos, const size_t len) const
{
      for (const uchar* const key_ = reinterpret_cast <const uchar*> (key);
           pos < len; ) { 
        size_t to = static_cast <size_t> (_array[from].base_); 
        to ^= key_[pos];
        if (_array[to].check != static_cast <int> (from)) {
          return CEDAR_NO_PATH;
        }
        ++pos;
        from = to;
      }
      const node n = _array[_array[from].base_ ^ 0];
      if (n.check != static_cast <int> (from)) return CEDAR_NO_VALUE;
      return n.base_;
}
```

Looking at disassembly of the generated codes:

```
objdump -D cedar.o
```

```assembly
   0:	f3 0f 1e fa          	endbr64 
   4:	49 89 f9             	mov    %rdi,%r9
   7:	48 8b 39             	mov    (%rcx),%rdi
   a:	48 8b 02             	mov    (%rdx),%rax
   d:	4d 8b 09             	mov    (%r9),%r9
  10:	49 39 f8             	cmp    %rdi,%r8
  13:	77 1d                	ja     32 <_ZNK2da4findEPKcRmS2_m+0x32>
  15:	eb 39                	jmp    50 <_ZNK2da4findEPKcRmS2_m+0x50>
  17:	66 0f 1f 84 00 00 00 	nopw   0x0(%rax,%rax,1)
  1e:	00 00 
  20:	48 83 c7 01          	add    $0x1,%rdi
  24:	48 89 39             	mov    %rdi,(%rcx)
  27:	48 89 02             	mov    %rax,(%rdx)
  2a:	48 8b 39             	mov    (%rcx),%rdi
  2d:	4c 39 c7             	cmp    %r8,%rdi
  30:	73 22                	jae    54 <_ZNK2da4findEPKcRmS2_m+0x54>
  32:	4d 63 14 c1          	movslq (%r9,%rax,8),%r10
  36:	49 89 c3             	mov    %rax,%r11
  39:	0f b6 04 3e          	movzbl (%rsi,%rdi,1),%eax
  3d:	4c 31 d0             	xor    %r10,%rax
  40:	4d 8d 14 c1          	lea    (%r9,%rax,8),%r10
  44:	45 39 5a 04          	cmp    %r11d,0x4(%r10)
  48:	74 d6                	je     20 <_ZNK2da4findEPKcRmS2_m+0x20>
  4a:	b8 ff ff ff ff       	mov    $0xffffffff,%eax
  4f:	c3                   	retq   
  50:	4d 8d 14 c1          	lea    (%r9,%rax,8),%r10
  54:	49 63 12             	movslq (%r10),%rdx
  57:	49 8d 14 d1          	lea    (%r9,%rdx,8),%rdx
  5b:	39 42 04             	cmp    %eax,0x4(%rdx)
  5e:	b8 fe ff ff ff       	mov    $0xfffffffe,%eax
  63:	0f 44 02             	cmove  (%rdx),%eax
  66:	c3                   	retq   
```

(For java we need [hsdis](https://github.com/cmuramoto/hsdis/raw/main/hsdis-amd64.so) in JAVA_HOME/lib)

```none
-XX:-TieredCompilation -XX:+UseParallelGC -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly
```

```assembly
============================= C2-compiled nmethod ==============================
----------------------------------- Assembly -----------------------------------

Compiled method (c2)   45518  355             com.nc.cedar.Cedar::get (138 bytes)
 total in heap  [0x00007f1f595e0d10,0x00007f1f595e15f0] = 2272
 relocation     [0x00007f1f595e0e70,0x00007f1f595e0e88] = 24
 main code      [0x00007f1f595e0ea0,0x00007f1f595e1140] = 672
 stub code      [0x00007f1f595e1140,0x00007f1f595e1158] = 24
 oops           [0x00007f1f595e1158,0x00007f1f595e1160] = 8
 metadata       [0x00007f1f595e1160,0x00007f1f595e1190] = 48
 scopes data    [0x00007f1f595e1190,0x00007f1f595e1278] = 232
 scopes pcs     [0x00007f1f595e1278,0x00007f1f595e15d8] = 864
 dependencies   [0x00007f1f595e15d8,0x00007f1f595e15e0] = 8
 nul chk table  [0x00007f1f595e15e0,0x00007f1f595e15f0] = 16

--------------------------------------------------------------------------------
[Constant Pool (empty)]

--------------------------------------------------------------------------------

[Entry Point]
  # {method} {0x00007f1f4a842ee8} 'get' '(JII)J' in 'com/nc/cedar/Cedar'
  # this:     rsi:rsi   = 'com/nc/cedar/Cedar'
  # parm0:    rdx:rdx   = long
  # parm1:    rcx       = int
  # parm2:    r8        = int
  #           [sp+0x40]  (sp of caller)
  0x00007f1f595e0ea0:   mov    0x8(%rsi),%r10d
  0x00007f1f595e0ea4:   movabs $0x800000000,%r11
  0x00007f1f595e0eae:   add    %r11,%r10
  0x00007f1f595e0eb1:   cmp    %r10,%rax
  0x00007f1f595e0eb4:   jne    0x00007f1f59501480           ;   {runtime_call ic_miss_stub}
  0x00007f1f595e0eba:   xchg   %ax,%ax
  0x00007f1f595e0ebc:   nopl   0x0(%rax)
[Verified Entry Point]
  0x00007f1f595e0ec0:   mov    %eax,-0x14000(%rsp)
  0x00007f1f595e0ec7:   push   %rbp
  0x00007f1f595e0ec8:   sub    $0x30,%rsp                   ;*synchronization entry
                                                            ; - com.nc.cedar.Cedar::get@-1 (line 508)
  0x00007f1f595e0ecc:   mov    %r8d,%r14d
  0x00007f1f595e0ecf:   mov    0x30(%rsi),%r11d             ;*getfield array {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@7 (line 510)
  0x00007f1f595e0ed3:   mov    0xc(%r12,%r11,8),%r10d       ; implicit exception: dispatches to 0x00007f1f595e1102
  0x00007f1f595e0ed8:   mov    0x20(%r12,%r10,8),%rbx       ;*invokevirtual getLong {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Bits::min@7 (line 56)
                                                            ; - com.nc.cedar.CedarBuffer::address@4 (line 186)
                                                            ; - com.nc.cedar.Cedar::get@10 (line 510)
  0x00007f1f595e0edd:   mov    %rbx,%rbp                    ;*invokevirtual getLong {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - jdk.internal.misc.Unsafe::getLong@3 (line 376)
                                                            ; - com.nc.cedar.Cedar::get@111 (line 523)
  0x00007f1f595e0ee0:   xor    %r8d,%r8d
  0x00007f1f595e0ee3:   cmp    %r14d,%ecx
  0x00007f1f595e0ee6:   jge    0x00007f1f595e1082           ;*if_icmplt {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@86 (line 513)
  0x00007f1f595e0eec:   mov    %rdx,%rax
  0x00007f1f595e0eef:   mov    %rdx,%r9                     ;*invokevirtual getByte {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - jdk.internal.misc.Unsafe::getByte@3 (line 322)
                                                            ; - com.nc.cedar.Cedar::get@38 (line 514)
  0x00007f1f595e0ef2:   mov    %ecx,%r10d
  0x00007f1f595e0ef5:   inc    %r10d
  0x00007f1f595e0ef8:   mov    %rbp,%rdi                    ;*getstatic U {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@89 (line 523)
  0x00007f1f595e0efb:   movslq %ecx,%r11
  0x00007f1f595e0efe:   movzbl (%r9,%r11,1),%esi
  0x00007f1f595e0f03:   xor    (%rdi),%esi
  0x00007f1f595e0f05:   movslq %esi,%rdx                    ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@45 (line 514)
  0x00007f1f595e0f08:   mov    %rdx,%r11
  0x00007f1f595e0f0b:   shl    $0x3,%r11
  0x00007f1f595e0f0f:   add    %rbx,%r11
  0x00007f1f595e0f12:   mov    %r11,%rdi                    ;*invokevirtual getInt {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - jdk.internal.misc.Unsafe::getInt@3 (line 364)
                                                            ; - com.nc.cedar.Cedar::get@62 (line 515)
  0x00007f1f595e0f15:   movslq 0x4(%rdi),%r13               ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@65 (line 515)
  0x00007f1f595e0f19:   cmp    %r8,%r13
  0x00007f1f595e0f1c:   nopl   0x0(%rax)
  0x00007f1f595e0f20:   jne    0x00007f1f595e10d0           ;*ifeq {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@69 (line 515)
  0x00007f1f595e0f26:   inc    %ecx                         ;*iinc {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@80 (line 520)
  0x00007f1f595e0f28:   cmp    %r10d,%ecx
  0x00007f1f595e0f2b:   jge    0x00007f1f595e0f32           ;*if_icmplt {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@86 (line 513)
  0x00007f1f595e0f2d:   mov    %rdx,%r8
  0x00007f1f595e0f30:   jmp    0x00007f1f595e0efb
  0x00007f1f595e0f32:   mov    %r14d,%esi
  0x00007f1f595e0f35:   add    $0xfffffffd,%esi
  0x00007f1f595e0f38:   mov    $0x80000000,%r10d
  0x00007f1f595e0f3e:   mov    %r14d,%r11d
  0x00007f1f595e0f41:   cmp    %esi,%r11d
  0x00007f1f595e0f44:   cmovl  %r10d,%esi
  0x00007f1f595e0f48:   cmp    %esi,%ecx
  0x00007f1f595e0f4a:   jge    0x00007f1f595e1008           ;*getstatic U {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@89 (line 523)
  0x00007f1f595e0f50:   movslq %ecx,%r10
  0x00007f1f595e0f53:   movzbl (%r9,%r10,1),%r10d
  0x00007f1f595e0f58:   xor    (%rdi),%r10d
  0x00007f1f595e0f5b:   movslq %r10d,%r8                    ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@45 (line 514)
  0x00007f1f595e0f5e:   mov    %r8,%r10
  0x00007f1f595e0f61:   shl    $0x3,%r10
  0x00007f1f595e0f65:   add    %rbx,%r10
  0x00007f1f595e0f68:   mov    %r10,%rdi                    ;*invokevirtual getInt {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - jdk.internal.misc.Unsafe::getInt@3 (line 364)
                                                            ; - com.nc.cedar.Cedar::get@62 (line 515)
  0x00007f1f595e0f6b:   movslq 0x4(%rdi),%r13               ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@65 (line 515)
  0x00007f1f595e0f6f:   cmp    %rdx,%r13
  0x00007f1f595e0f72:   jne    0x00007f1f595e1087           ;*ifeq {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@69 (line 515)
  0x00007f1f595e0f78:   mov    %ecx,%r10d
  0x00007f1f595e0f7b:   inc    %r10d                        ;*iinc {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@80 (line 520)
  0x00007f1f595e0f7e:   movslq %r10d,%rdx
  0x00007f1f595e0f81:   movzbl (%r9,%rdx,1),%edx
  0x00007f1f595e0f86:   xor    (%rdi),%edx
  0x00007f1f595e0f88:   movslq %edx,%rdx                    ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@45 (line 514)
  0x00007f1f595e0f8b:   mov    %rdx,%rdi
  0x00007f1f595e0f8e:   shl    $0x3,%rdi
  0x00007f1f595e0f92:   add    %rbx,%rdi                    ;*invokevirtual getInt {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - jdk.internal.misc.Unsafe::getInt@3 (line 364)
                                                            ; - com.nc.cedar.Cedar::get@62 (line 515)
  0x00007f1f595e0f95:   movslq 0x4(%rdi),%r13               ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@65 (line 515)
  0x00007f1f595e0f99:   cmp    %r8,%r13
  0x00007f1f595e0f9c:   nopl   0x0(%rax)
  0x00007f1f595e0fa0:   jne    0x00007f1f595e1093           ;*ifeq {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@69 (line 515)
  0x00007f1f595e0fa6:   mov    %ecx,%r10d
  0x00007f1f595e0fa9:   add    $0x2,%r10d                   ;*iinc {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@80 (line 520)
  0x00007f1f595e0fad:   movslq %r10d,%r8
  0x00007f1f595e0fb0:   movzbl (%r9,%r8,1),%r8d
  0x00007f1f595e0fb5:   xor    (%rdi),%r8d
  0x00007f1f595e0fb8:   movslq %r8d,%r8                     ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@45 (line 514)
  0x00007f1f595e0fbb:   mov    %r8,%rdi
  0x00007f1f595e0fbe:   shl    $0x3,%rdi
  0x00007f1f595e0fc2:   add    %rbx,%rdi                    ;*invokevirtual getInt {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - jdk.internal.misc.Unsafe::getInt@3 (line 364)
                                                            ; - com.nc.cedar.Cedar::get@62 (line 515)
  0x00007f1f595e0fc5:   movslq 0x4(%rdi),%r13               ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@65 (line 515)
  0x00007f1f595e0fc9:   cmp    %rdx,%r13
  0x00007f1f595e0fcc:   jne    0x00007f1f595e108a           ;*ifeq {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@69 (line 515)
  0x00007f1f595e0fd2:   mov    %ecx,%r10d
  0x00007f1f595e0fd5:   add    $0x3,%r10d                   ;*iinc {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@80 (line 520)
  0x00007f1f595e0fd9:   movslq %r10d,%rdx
  0x00007f1f595e0fdc:   movzbl (%r9,%rdx,1),%edx
  0x00007f1f595e0fe1:   xor    (%rdi),%edx
  0x00007f1f595e0fe3:   movslq %edx,%rdx                    ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@45 (line 514)
  0x00007f1f595e0fe6:   mov    %rdx,%rdi
  0x00007f1f595e0fe9:   shl    $0x3,%rdi
  0x00007f1f595e0fed:   add    %rbx,%rdi                    ;*invokevirtual getInt {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - jdk.internal.misc.Unsafe::getInt@3 (line 364)
                                                            ; - com.nc.cedar.Cedar::get@62 (line 515)
  0x00007f1f595e0ff0:   movslq 0x4(%rdi),%r13               ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@65 (line 515)
  0x00007f1f595e0ff4:   cmp    %r8,%r13
  0x00007f1f595e0ff7:   jne    0x00007f1f595e1093           ;*ifeq {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@69 (line 515)
  0x00007f1f595e0ffd:   add    $0x4,%ecx                    ;*iinc {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@80 (line 520)
  0x00007f1f595e1000:   cmp    %esi,%ecx
  0x00007f1f595e1002:   jl     0x00007f1f595e0f50           ;*if_icmplt {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@86 (line 513)
  0x00007f1f595e1008:   cmp    %r11d,%ecx
  0x00007f1f595e100b:   jge    0x00007f1f595e104a
  0x00007f1f595e100d:   data16 xchg %ax,%ax                 ;*getstatic U {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@89 (line 523)
  0x00007f1f595e1010:   movslq %ecx,%r10
  0x00007f1f595e1013:   movzbl (%r9,%r10,1),%r10d
  0x00007f1f595e1018:   xor    (%rdi),%r10d
  0x00007f1f595e101b:   movslq %r10d,%r8                    ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@45 (line 514)
  0x00007f1f595e101e:   mov    %r8,%r10
  0x00007f1f595e1021:   shl    $0x3,%r10
  0x00007f1f595e1025:   add    %rbx,%r10
  0x00007f1f595e1028:   mov    %r10,%rdi                    ;*invokevirtual getInt {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - jdk.internal.misc.Unsafe::getInt@3 (line 364)
                                                            ; - com.nc.cedar.Cedar::get@62 (line 515)
  0x00007f1f595e102b:   movslq 0x4(%rdi),%r13               ;*i2l {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@65 (line 515)
  0x00007f1f595e102f:   cmp    %rdx,%r13
  0x00007f1f595e1032:   jne    0x00007f1f595e10f8           ;*ifeq {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@69 (line 515)
  0x00007f1f595e1038:   inc    %ecx                         ;*iinc {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@80 (line 520)
  0x00007f1f595e103a:   nopw   0x0(%rax,%rax,1)
  0x00007f1f595e1040:   cmp    %r11d,%ecx
  0x00007f1f595e1043:   jge    0x00007f1f595e104d           ;*if_icmplt {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@86 (line 513)
  0x00007f1f595e1045:   mov    %r8,%rdx
  0x00007f1f595e1048:   jmp    0x00007f1f595e1010
  0x00007f1f595e104a:   mov    %rdx,%r8                     ;*getstatic U {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@89 (line 523)
  0x00007f1f595e104d:   mov    (%rdi),%r10d
  0x00007f1f595e1050:   shl    $0x3,%r10d
  0x00007f1f595e1054:   movslq %r10d,%r10
  0x00007f1f595e1057:   mov    0x0(%rbp,%r10,1),%r11        ;*invokevirtual getLong {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - jdk.internal.misc.Unsafe::getLong@3 (line 376)
                                                            ; - com.nc.cedar.Cedar::get@111 (line 523)
  0x00007f1f595e105c:   mov    %r11,%r10
  0x00007f1f595e105f:   shr    $0x20,%r10                   ;*lushr {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@120 (line 525)
  0x00007f1f595e1063:   cmp    %r8,%r10
  0x00007f1f595e1066:   jne    0x00007f1f595e10d8           ;*ifeq {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@124 (line 525)
  0x00007f1f595e106c:   mov    %r11d,%eax                   ;*land {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@136 (line 529)
  0x00007f1f595e106f:   add    $0x30,%rsp
  0x00007f1f595e1073:   pop    %rbp
  0x00007f1f595e1074:   cmp    0x340(%r15),%rsp             ;   {poll_return}
  0x00007f1f595e107b:   ja     0x00007f1f595e110c
  0x00007f1f595e1081:   retq   
  0x00007f1f595e1082:   mov    %rbp,%rdi
  0x00007f1f595e1085:   jmp    0x00007f1f595e104d
  0x00007f1f595e1087:   mov    %ecx,%r10d
  0x00007f1f595e108a:   mov    %rdx,%r9
  0x00007f1f595e108d:   mov    %r8,%rdx
  0x00007f1f595e1090:   mov    %r9,%r8
  0x00007f1f595e1093:   mov    %r8,%r9
  0x00007f1f595e1096:   mov    %rdx,%r8
  0x00007f1f595e1099:   mov    %r9,%rdx
  0x00007f1f595e109c:   cmp    %rdx,%r13
  0x00007f1f595e109f:   mov    $0xffffffff,%ebp
  0x00007f1f595e10a4:   jl     0x00007f1f595e10ae
  0x00007f1f595e10a6:   setne  %bpl
  0x00007f1f595e10aa:   movzbl %bpl,%ebp                    ;*lcmp {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@68 (line 515)
  0x00007f1f595e10ae:   mov    $0xffffff45,%esi
  0x00007f1f595e10b3:   mov    %r10d,(%rsp)
  0x00007f1f595e10b7:   mov    %r8,0x8(%rsp)
  0x00007f1f595e10bc:   mov    %rbx,0x10(%rsp)
  0x00007f1f595e10c1:   mov    %rax,0x18(%rsp)
  0x00007f1f595e10c6:   mov    %r11d,0x4(%rsp)
  0x00007f1f595e10cb:   callq  0x00007f1f59506d00           ; ImmutableOopMap {}
                                                            ;*ifeq {reexecute=1 rethrow=0 return_oop=0}
                                                            ; - (reexecute) com.nc.cedar.Cedar::get@69 (line 515)
                                                            ;   {runtime_call UncommonTrapBlob}
  0x00007f1f595e10d0:   mov    %ecx,%r10d
  0x00007f1f595e10d3:   mov    %r14d,%r11d
  0x00007f1f595e10d6:   jmp    0x00007f1f595e1093
  0x00007f1f595e10d8:   cmp    %r8,%r10
  0x00007f1f595e10db:   mov    $0xffffffff,%ebp
  0x00007f1f595e10e0:   jl     0x00007f1f595e10ea
  0x00007f1f595e10e2:   setne  %bpl
  0x00007f1f595e10e6:   movzbl %bpl,%ebp                    ;*lcmp {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@123 (line 525)
  0x00007f1f595e10ea:   mov    $0xffffff45,%esi
  0x00007f1f595e10ef:   mov    %r11,(%rsp)
  0x00007f1f595e10f3:   callq  0x00007f1f59506d00           ; ImmutableOopMap {}
                                                            ;*ifeq {reexecute=1 rethrow=0 return_oop=0}
                                                            ; - (reexecute) com.nc.cedar.Cedar::get@124 (line 525)
                                                            ;   {runtime_call UncommonTrapBlob}
  0x00007f1f595e10f8:   mov    %ecx,%r10d
  0x00007f1f595e10fb:   nopl   0x0(%rax,%rax,1)
  0x00007f1f595e1100:   jmp    0x00007f1f595e109c
  0x00007f1f595e1102:   mov    $0xfffffff6,%esi
  0x00007f1f595e1107:   callq  0x00007f1f59506d00           ; ImmutableOopMap {}
                                                            ;*invokevirtual address {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - com.nc.cedar.Cedar::get@10 (line 510)
                                                            ;   {runtime_call UncommonTrapBlob}
  0x00007f1f595e110c:   movabs $0x7f1f595e1074,%r10         ;   {internal_word}
  0x00007f1f595e1116:   mov    %r10,0x358(%r15)
  0x00007f1f595e111d:   jmpq   0x00007f1f59507e00           ;   {runtime_call SafepointBlob}
  0x00007f1f595e1122:   hlt    
  0x00007f1f595e1123:   hlt    
  0x00007f1f595e1124:   hlt    
  0x00007f1f595e1125:   hlt    
  0x00007f1f595e1126:   hlt    
  0x00007f1f595e1127:   hlt    
  0x00007f1f595e1128:   hlt    
  0x00007f1f595e1129:   hlt    
  0x00007f1f595e112a:   hlt    
  0x00007f1f595e112b:   hlt    
  0x00007f1f595e112c:   hlt    
  0x00007f1f595e112d:   hlt    
  0x00007f1f595e112e:   hlt    
  0x00007f1f595e112f:   hlt    
  0x00007f1f595e1130:   hlt    
  0x00007f1f595e1131:   hlt    
  0x00007f1f595e1132:   hlt    
  0x00007f1f595e1133:   hlt    
  0x00007f1f595e1134:   hlt    
  0x00007f1f595e1135:   hlt    
  0x00007f1f595e1136:   hlt    
  0x00007f1f595e1137:   hlt    
  0x00007f1f595e1138:   hlt    
  0x00007f1f595e1139:   hlt    
  0x00007f1f595e113a:   hlt    
  0x00007f1f595e113b:   hlt    
  0x00007f1f595e113c:   hlt    
  0x00007f1f595e113d:   hlt    
  0x00007f1f595e113e:   hlt    
  0x00007f1f595e113f:   hlt    
[Exception Handler]
  0x00007f1f595e1140:   jmpq   0x00007f1f59518f00           ;   {no_reloc}
[Deopt Handler Code]
  0x00007f1f595e1145:   callq  0x00007f1f595e114a
  0x00007f1f595e114a:   subq   $0x5,(%rsp)
  0x00007f1f595e114f:   jmpq   0x00007f1f595070a0           ;   {runtime_call DeoptimizationBlob}
  0x00007f1f595e1154:   hlt    
  0x00007f1f595e1155:   hlt    
  0x00007f1f595e1156:   hlt    
  0x00007f1f595e1157:   hlt    
--------------------------------------------------------------------------------
```

We can see why it's nearly impossible to match C++. Even with the amazing amount of inlining performed by C2, with 0 function calls in the hot path, the code (discarding deoptimization traps) is about 4 times larger than the same C code.

E.g., to fetch the 'check' field from memory (U.getInt(addr + (to << 3) + 4) -> array[to].check) Java needs 4 instructions to load the check field plus one to sign extend and store it in r13:

```assembly
  0x00007f1f595e0f08:   mov    %rdx,%r11
  0x00007f1f595e0f0b:   shl    $0x3,%r11
  0x00007f1f595e0f0f:   add    %rbx,%r11
  0x00007f1f595e0f12:   mov    %r11,%rdi
  
  0x00007f1f595e0f15:   movslq 0x4(%rdi),%r13

  0x00007f1f595e0f19:   cmp    %r8,%r13 ;if (U.getInt(addr + (to << 3) + 4) != from) {...}

```

vs 1 + 1 instruction from C code:

```assembly
  50:	4d 8d 14 c1          	lea    (%r9,%rax,8),%r10
  
  54:	49 63 12             	movslq (%r10),%rdx
  57:	49 8d 14 d1          	lea    (%r9,%rdx,8),%rdx
  
  5b:	39 42 04             	cmp    %eax,0x4(%rdx)  ;if (_array[to].check != static_cast <int> (from)) { ... }
```

Changing the code a bit to

```java
long get(long base, int pos, int end) {
  var from = 0L;
  var to = 0L;
  var addr = this.array.address();
  var addr_4 = addr + 4L; // 

  while (pos < end) {
    to = U.getInt(addr + (from << 3)) ^ u32(U.getByte(base + pos));
    if (U.getInt(addr_4 + (to << 3)) != from) {
      return ABSENT;
    }
    from = to;
    pos++;
  }

  to = U.getLong(addr + (U.getInt(addr + (from << 3)) << 3));

  if ((to >>> 32) != from) {
    return NO_VALUE;
  }

  return to & 0xFFFFFFFFL;
}
```

We can get rid of one instruction:

```assembly
  0x00007f69ac2e750b:   mov    %rdx,%rdi
  0x00007f69ac2e750e:   shl    $0x3,%rdi
  0x00007f69ac2e7512:   add    %rbx,%rdi                    
  0x00007f69ac2e7515:   movslq 0x4(%rdi),%r13               
  
  0x00007f69ac2e7519:   cmp    %r8,%r13
```
, which seems the best hotspot can do.

Also, the method **find** wasn't inlined in the **run** loop, so there's always a safepoint check that *may* be triggered right before the method exit, even with GC free code:

```assembly
  0x00007f1f595e1074:   cmp    0x340(%r15),%rsp             ;   {poll_return}
  0x00007f1f595e107b:   ja     0x00007f1f595e110c
  0x00007f1f595e1081:   retq   
```

In order to test Azul's claims about [Falcon JIT Compiler](https://www.azul.com/products/components/falcon-jit-compiler/) being faster than C2, we adapted the code for Azulś jdk-15 and run the same benchmark. Indeed the generated code is about half the size of C2:

```assembly
Disassembling com.nc.cedar.Cedar::get:
-----------
0x3002aa60: ff f0                             pushq    %rax                         
0x3002aa62: 49 89 f0                          movq    %rsi, %r8                     
0x3002aa65: 48 89 fe                          movq    %rdi, %rsi                    
0x3002aa68: 65 83 3c 25 68 00 00 00 00        cmpl    $0, %gs:104                   ; thread:[104] = _please_self_suspend
0x3002aa71: 75 6f                             jne    111                            ; 0x3002aae2
0x3002aa73: 48 8b 46 30                       movq    48(%rsi), %rax                
0x3002aa77: 48 bf 48 00 f8 2f 00 00 00 00     movabsq    $804782152, %rdi           ; 0x2ff80048 = 
                                                                                    ; 804782152 = clearable_gc_phase_trap_mask
0x3002aa81: 48 85 07                          testq    %rax, (%rdi)                 
0x3002aa84: 75 6a                             jne    106                            ; 0x3002aaf0
0x3002aa86: 4c 8b 50 08                       movq    8(%rax), %r10                 
0x3002aa8a: 39 ca                             cmpl    %ecx, %edx                    
0x3002aa8c: 7d 50                             jge    80                             ; 0x3002aade
0x3002aa8e: 48 63 d2                          movslq    %edx, %rdx                  
0x3002aa91: 4c 63 c9                          movslq    %ecx, %r9                   
0x3002aa94: 31 c9                             xorl    %ecx, %ecx                    
0x3002aa96: 48 b8 00 00 00 00 02 00 00 00     movabsq    $8589934592, %rax          ; 0x200000000 = 
0x3002aaa0: 48 89 cf                          movq    %rcx, %rdi                    
0x3002aaa3: 41 0f b6 0c 10                    movzbl    (%r8,%rdx), %ecx            
0x3002aaa8: 41 33 0c fa                       xorl    (%r10,%rdi,8), %ecx           
0x3002aaac: 48 63 c9                          movslq    %ecx, %rcx                  
0x3002aaaf: 49 63 74 ca 04                    movslq    4(%r10,%rcx,8), %rsi        
0x3002aab4: 48 39 f7                          cmpq    %rsi, %rdi                    
0x3002aab7: 75 69                             jne    105                            ; 0x3002ab22
0x3002aab9: 48 ff c2                          incq    %rdx                          
0x3002aabc: 4c 39 ca                          cmpq    %r9, %rdx                     
0x3002aabf: 7c df                             jl    -33                             ; 0x3002aaa0
0x3002aac1: 41 8b 04 ca                       movl    (%r10,%rcx,8), %eax           
0x3002aac5: c1 e0 03                          shll    $3, %eax                      
0x3002aac8: 48 98                             cltq                                  
0x3002aaca: 49 8b 04 02                       movq    (%r10,%rax), %rax             
0x3002aace: 48 89 c2                          movq    %rax, %rdx                    
0x3002aad1: 48 c1 ea 20                       shrq    $32, %rdx                     
0x3002aad5: 48 39 ca                          cmpq    %rcx, %rdx                    
0x3002aad8: 75 3e                             jne    62                             ; 0x3002ab18
0x3002aada: 89 c0                             movl    %eax, %eax                    
0x3002aadc: 59                                popq    %rcx                          
0x3002aadd: c3                                retq                                  
0x3002aade: 31 c9                             xorl    %ecx, %ecx                    
0x3002aae0: eb df                             jmp    -33                            ; 0x3002aac1
0x3002aae2: 48 b8 00 87 01 30 00 00 00 00     movabsq    $805406464, %rax           ; 0x30018700 = StubRoutines::safepoint_handler
0x3002aaec: ff d0                             callq    *%rax                        ; 0x30018700 = StubRoutines::safepoint_handler
0x3002aaee: eb 83                             jmp    -125                           ; 0x3002aa73
0x3002aaf0: 48 83 c6 30                       addq    $48, %rsi                     
0x3002aaf4: 49 b9 80 b4 00 30 00 00 00 00     movabsq    $805352576, %r9            ; 0x3000b480 = StubRoutines::lvb_handler_for_call
0x3002aafe: 48 89 c7                          movq    %rax, %rdi                    
0x3002ab01: 41 ff d1                          callq    *%r9                         ; 0x3000b480 = StubRoutines::lvb_handler_for_call
0x3002ab04: eb 80                             jmp    -128                           ; 0x3002aa86
0x3002ab06: 48 b8 00 c7 00 30 00 00 00 00     movabsq    $805357312, %rax           ; 0x3000c700 = StubRoutines::uncommon_trap_for_falcon
0x3002ab10: 41 bb 0a 00 00 00                 movl    $10, %r11d                    
0x3002ab16: ff d0                             callq    *%rax                        ; 0x3000c700 = StubRoutines::uncommon_trap_for_falcon
0x3002ab18: 48 b8 00 00 00 00 01 00 00 00     movabsq    $4294967296, %rax          ; 0x100000000 = 
0x3002ab22: 59                                popq    %rcx                          
0x3002ab23: c3                                retq                                  
-----------
```
and is in fact, slightly faster, but still no match to C++. In both azul and hotspot it wasn't possible to trap any Safepoint jitter.

In summary, replacing reads from memory segments and byte arrays with Unsafe, disregarding any bounds checks, we end up with:

| Dataset  | #keys| #distinct | ns/op(C2) | % vs C | ns/op(Falcon) | % vs C |
| --- | --- | --- | --- | --- | --- | --- |
| [distinct](http://web.archive.org/web/20120206015921/http://www.naskitis.com/distinct_1.bz2)  | 28.772.169 | 28.772.169| 280.57 | 20.11% slower | 269.23 | 13.09% slower | 
| [skew](http://web.archive.org/web/20120206015921/http://www.naskitis.com/skew1_1.bz2)  | 177.999.203 | 612.219 | 38.66 | 22.53% slower | 35.33 | 10.69% slower |



### Sampling with small Strings (avg 11 bytes)

Even though Tries can hold keys of "any" length, they are not meant to hold very large keys. To get an estimate of the throughput for my main use case (key lengths in between 10 and 12 bytes), I took a sample of 64 keys on heap with average length of 11.00 (slightly larger than the dataset average which is 9.58) and 
run:

```java
long run(Cedar|ReducedCedar c) {
  var samples = this.samples;
  var ops = ids;
  var query = 0;
  for (var i = 0; i < ops; i++) {
    var now = System.nanoTime();
    for (var key : samples) {
      assertTrue((c.find(key) & BaseCedar.ABSENT_OR_NO_VALUE) == 0);
    }
    query += (System.nanoTime() - now);
    shuffle(samples);
  }
}

double avg = run(...)/(ops*sampes.length);
```

For this sample the numbers are:

| Dataset  | #keys| #samples| #operations | ns/op (C2-std) | ns/op (C2-red) | ns/op (Falcon-std) | ns/op (Falcon-red) |
| --- | --- | --- | --- | --- |
| [distinct](http://web.archive.org/web/20120206015921/http://www.naskitis.com/distinct_1.bz2)  | 28.772.169 | 64 | 1.841.418.816 | 47.47 | 94.22 | 37.73 | 47.53 |

Azul's jdk-15 version uses direct Unsafe calls, whereas jdk-16 uses MemorySegments. Bound's checking has it's toll and it shows mostly in ReducedTrie, but for the standard implementation it's not a very high one to pay.
