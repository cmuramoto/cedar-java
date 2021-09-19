# cedar-java
A java backport of rust's [cedarwood](https://github.com/MnO2/cedarwood), an eficient updatable double array trie.

This trie works like a SortedMap<String,int> and it's lookups run in O(k), where k is the lenght of the key.

The implementation uses preview features (records) and its underlying data structures are based on (native) MemorySegments of the incubator *foreign-api*, but can easilly be ported to a ByteBuffer based implementation. 

By using MemorySegments to represent the trie's arrays we achieve memory density (and bypass pointer dereferences) which is currently impossible with vanilla java OOP and won't be until valhalla goes GA.

Another advantage of representing data off-heap is that the trie can be trivially be loaded/stored with Memory Mapping, which allows translates in millisecond persistence even for very large tries.

