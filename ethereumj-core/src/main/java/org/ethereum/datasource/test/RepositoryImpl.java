package org.ethereum.datasource.test;

import org.ethereum.core.AccountState;
import org.ethereum.core.Block;
import org.ethereum.core.Repository;
import org.ethereum.crypto.HashUtil;
import org.ethereum.datasource.Serializer;
import org.ethereum.db.ByteArrayWrapper;
import org.ethereum.db.ContractDetails;
import org.ethereum.util.RLP;
import org.ethereum.util.Value;
import org.ethereum.vm.DataWord;
import org.spongycastle.util.encoders.Hex;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.*;

/**
 * Created by Anton Nashatyrev on 07.10.2016.
 */
public class RepositoryImpl implements Repository {

    private RepositoryImpl parent;

    private Source<byte[], AccountState> accountStateCache;
    private Source<byte[], byte[]> codeCache;
    private MultiCache<? extends Source<DataWord, DataWord>> storageCache;

    public RepositoryImpl(Source<byte[], AccountState> accountStateCache, Source<byte[], byte[]> codeCache,
                          MultiCache<? extends Source<DataWord, DataWord>> storageCache) {
        this.accountStateCache = accountStateCache;
        this.codeCache = codeCache;
        this.storageCache = storageCache;
    }

    public static RepositoryImpl createNew(Source<byte[], byte[]> stateDS) {
        return createFromStateDS(stateDS, null);
    }
    public static RepositoryImpl createFromStateDS(final Source<byte[], byte[]> stateDS, byte[] root) {
        final CachedSource.SimpleBytesKey<byte[]> snapshotCache = new CachedSource.SimpleBytesKey<>(stateDS);

        final CachedSource.BytesKey<Value, byte[]> trieCache = new CachedSource.BytesKey<>
                (snapshotCache, new TrieCacheSerializer());
        trieCache.cacheReads = false;
        trieCache.noDelete = true;
        final TrieImpl trie = createTrie(trieCache, root);

        final CachedSource.BytesKey<AccountState, byte[]> accountStateCache =
                new CachedSource.BytesKey<>(trie, new AccountStateSerializer());
        CachedSource.SimpleBytesKey<byte[]> codeCache = new CachedSource.SimpleBytesKey<>(snapshotCache);

        class MultiTrieCache extends CachedSource<DataWord, DataWord, byte[], byte[]> {
            byte[] accountAddress;
            Trie<byte[]> trie;

            public MultiTrieCache(byte[] accountAddress, Trie<byte[]> trie) {
                super(trie, new WordSerializer(), new TrieWordSerializer());
                this.accountAddress = accountAddress;
                this.trie = trie;
            }
        }

        final MultiCache<MultiTrieCache> storageCache = new MultiCache<MultiTrieCache>(null) {
            @Override
            protected MultiTrieCache create(byte[] key, MultiTrieCache srcCache) {
                AccountState accountState = accountStateCache.get(key);
//                if (accountState == null) return null;
                TrieImpl storageTrie = createTrie(trieCache, accountState == null ? null : accountState.getStateRoot());
                return new MultiTrieCache(key, storageTrie);
            }

            @Override
            protected boolean flushChild(MultiTrieCache childCache) {
                if (super.flushChild(childCache)) {
//                RepositoryNew.this.updateAccountStateRoot(childCache.accountAddress, childCache.trie.getRootHash());
                    byte[] rootHash = childCache.trie.getRootHash();
                    AccountState state = accountStateCache.get(childCache.accountAddress).clone();
                    state.setStateRoot(rootHash);
                    accountStateCache.put(childCache.accountAddress, state);
                    return true;
                } else {
                    return false;
                }
            }
        };
        return new RepositoryImpl(accountStateCache, codeCache, storageCache) {
            @Override
            public void commit() {
                super.commit();

                trieCache.flush();
                snapshotCache.flush();
            }

            @Override
            public byte[] getRoot() {
                super.commit();
                return trie.getRootHash();
            }

            @Override
            public Repository getSnapshotTo(byte[] root) {
                return createFromStateDS(stateDS, root);
            }

            @Override
            public String dumpStateTrie() {
                return trie.getTrieDump();
            }
        };
    }

    private static TrieImpl createTrie(CachedSource.BytesKey<Value, byte[]> trieCache, byte[] root) {
        return new SecureTrie(trieCache, root);
//        return new TrieImpl(trieCache, root);
    }

    public String getTrieDump() {
        return dumpStateTrie();
    }

    public String dumpStateTrie() {
        throw new RuntimeException("Not supported");
    }

    @Override
    public AccountState createAccount(byte[] addr) {
        AccountState state = new AccountState(BigInteger.ZERO, BigInteger.ZERO);
        accountStateCache.put(addr, state);
        return state;
    }

    @Override
    public boolean isExist(byte[] addr) {
        return getAccountState(addr) != null;
    }

    @Override
    public AccountState getAccountState(byte[] addr) {
        return accountStateCache.get(addr);
    }

    private AccountState getOrCreateAccountState(byte[] addr) {
        AccountState ret = accountStateCache.get(addr);
        if (ret == null) {
            ret = createAccount(addr);
        }
        return ret;
    }

    @Override
    public void delete(byte[] addr) {
        accountStateCache.delete(addr);
    }

    @Override
    public BigInteger increaseNonce(byte[] addr) {
        AccountState accountState = getOrCreateAccountState(addr).clone();
        accountState.incrementNonce();
        accountStateCache.put(addr, accountState);
        return accountState.getNonce();
    }

    @Override
    public BigInteger getNonce(byte[] addr) {
        AccountState accountState = getAccountState(addr);
        return accountState == null ? BigInteger.ZERO : accountState.getNonce();
    }

    @Override
    public ContractDetails getContractDetails(byte[] addr) {
        return new ContractDetailsImpl(addr);
    }

    @Override
    public boolean hasContractDetails(byte[] addr) {
        return getContractDetails(addr) != null;
    }

    @Override
    public void saveCode(byte[] addr, byte[] code) {
        byte[] codeHash = HashUtil.sha3(code);
        codeCache.put(codeHash, code);
        AccountState accountState = getOrCreateAccountState(addr).clone();
        accountState.setCodeHash(codeHash);
        accountStateCache.put(addr, accountState);
    }

    @Override
    public byte[] getCode(byte[] addr) {
        AccountState accountState = getAccountState(addr);
        return accountState == null ? new byte[0] : codeCache.get(accountState.getCodeHash());
    }

    @Override
    public void addStorageRow(byte[] addr, DataWord key, DataWord value) {
        getOrCreateAccountState(addr);

        Source<DataWord, DataWord> contractStorage = storageCache.get(addr);
        contractStorage.put(key, value);
    }

    @Override
    public DataWord getStorageValue(byte[] addr, DataWord key) {
        AccountState accountState = getAccountState(addr);
        return accountState == null ? null : storageCache.get(addr).get(key);
    }

    @Override
    public BigInteger getBalance(byte[] addr) {
        AccountState accountState = getAccountState(addr);
        return accountState == null ? BigInteger.ZERO : accountState.getBalance();
    }

    @Override
    public BigInteger addBalance(byte[] addr, BigInteger value) {
        AccountState accountState = getOrCreateAccountState(addr).clone();
        accountState.addToBalance(value);
        accountStateCache.put(addr, accountState);
        return accountState.getBalance();
    }

    @Override
    public RepositoryImpl startTracking() {
        CachedSource.SimpleBytesKey<AccountState> trackAccountStateCache = new CachedSource.SimpleBytesKey<>(accountStateCache);
        CachedSource.SimpleBytesKey<byte[]> trackCodeCache = new CachedSource.SimpleBytesKey<>(codeCache);
        MultiCache<? extends Source<DataWord, DataWord>> trackStorageCache = new MultiCache(storageCache) {
            @Override
            protected Source create(byte[] key, Source srcCache) {
                return new Simple<>(srcCache);
            }
        };

        RepositoryImpl ret = new RepositoryImpl(trackAccountStateCache, trackCodeCache, trackStorageCache);
        ret.parent = this;
        return ret;
    }

    @Override
    public Repository getSnapshotTo(byte[] root) {
        return parent.getSnapshotTo(root);
    }

    @Override
    public void commit() {
        storageCache.flush();
        codeCache.flush();
        accountStateCache.flush();
    }

    @Override
    public void rollback() {
        // nothing to do, will be GCed
    }

    @Override
    public byte[] getRoot() {
        // TODO
        throw new RuntimeException("TODO");
    }

    static class AccountStateSerializer implements Serializer<AccountState, byte[]> {
        @Override
        public byte[] serialize(AccountState object) {
            return object.getEncoded();
        }

        @Override
        public AccountState deserialize(byte[] stream) {
            return stream == null || stream.length == 0 ? null : new AccountState(stream);
        }
    }

    static class WordSerializer implements Serializer<DataWord, byte[]> {
        @Override
        public byte[] serialize(DataWord object) {
            return object.getData();
        }

        @Override
        public DataWord deserialize(byte[] stream) {
            return new DataWord(stream);
        }
    }

    static class TrieWordSerializer implements Serializer<DataWord, byte[]> {
        @Override
        public byte[] serialize(DataWord object) {
            return RLP.encodeElement(object.getNoLeadZeroesData());
        }

        @Override
        public DataWord deserialize(byte[] stream) {
            if (stream == null || stream.length == 0) return null;
            byte[] dataDecoded = RLP.decode2(stream).get(0).getRLPData();
            return new DataWord(dataDecoded);
        }
    }

    static class TrieCacheSerializer implements Serializer<Value, byte[]> {
        @Override
        public byte[] serialize(Value object) {
            return object.encode();
        }

        @Override
        public Value deserialize(byte[] stream) {
            return Value.fromRlpEncoded(stream);
        }
    }

    class ContractDetailsImpl implements ContractDetails {
        private byte[] address;

        public ContractDetailsImpl(byte[] address) {
            this.address = address;
        }

        @Override
        public void put(DataWord key, DataWord value) {
            RepositoryImpl.this.addStorageRow(address, key, value);
        }

        @Override
        public DataWord get(DataWord key) {
            return RepositoryImpl.this.getStorageValue(address, key);
        }

        @Override
        public byte[] getCode() {
            return RepositoryImpl.this.getCode(address);
        }

        @Override
        public byte[] getCode(byte[] codeHash) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void setCode(byte[] code) {
            RepositoryImpl.this.saveCode(address, code);
        }

        @Override
        public byte[] getStorageHash() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void decode(byte[] rlpCode) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void setDirty(boolean dirty) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void setDeleted(boolean deleted) {
            RepositoryImpl.this.delete(address);
        }

        @Override
        public boolean isDirty() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public boolean isDeleted() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public byte[] getEncoded() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public int getStorageSize() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public Set<DataWord> getStorageKeys() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public Map<DataWord, DataWord> getStorage(@Nullable Collection<DataWord> keys) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public Map<DataWord, DataWord> getStorage() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void setStorage(List<DataWord> storageKeys, List<DataWord> storageValues) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void setStorage(Map<DataWord, DataWord> storage) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public byte[] getAddress() {
            return address;
        }

        @Override
        public void setAddress(byte[] address) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public ContractDetails clone() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void syncStorage() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public ContractDetails getSnapshotTo(byte[] hash) {
            throw new RuntimeException("Not supported");
        }
    }


    @Override
    public Set<byte[]> getAccountsKeys() {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void dumpState(Block block, long gasUsed, int txNumber, byte[] txHash) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void flush() {
        throw new RuntimeException("Not supported");
    }


    @Override
    public void flushNoReconnect() {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void syncToRoot(byte[] root) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public boolean isClosed() {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void close() {
    }

    @Override
    public void reset() {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void updateBatch(HashMap<ByteArrayWrapper, AccountState> accountStates, HashMap<ByteArrayWrapper, ContractDetails> contractDetailes) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void loadAccount(byte[] addr, HashMap<ByteArrayWrapper, AccountState> cacheAccounts, HashMap<ByteArrayWrapper, ContractDetails> cacheDetails) {
        throw new RuntimeException("Not supported");
    }
}
