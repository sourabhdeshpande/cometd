/*
 * Copyright (c) 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.oort;

import java.util.Collections;
import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.common.MarkedReference;

/**
 * A specialized oort object whose entity is a {@link ConcurrentMap}.
 * <p/>
 * {@link OortMap} specializes {@code OortObject} and allows optimized replication of map entries
 * across the cluster: instead of replicating the whole map, that may be contain a lot of entries,
 * only entries that are modified are replicated.
 * <p/>
 * Applications can use {@link #putAndShare(String, Object)} and {@link #removeAndShare(String)}
 * to broadcast changes related to single entries, as well as {@link #setAndShare(Object)} to
 * change the whole map.
 * <p/>
 * When a single entry is changed, {@link EntryListener}s are notified.
 * {@link DeltaListener} converts whole map updates triggered by {@link #setAndShare(Object)}
 * into events for {@link EntryListener}s, giving applications a single listener type to implement
 * their business logic.
 *
 * @param <V> the value type
 */
public class OortMap<V> extends OortObject<ConcurrentMap<String, V>>
{
    private static final String TYPE_FIELD_ENTRY_VALUE = "oort.map.entry";
    private static final String ACTION_FIELD_PUT_VALUE = "oort.map.put";
    private static final String ACTION_FIELD_REMOVE_VALUE = "oort.map.remove";
    private static final String KEY_FIELD = "oort.map.key";
    private static final String VALUE_FIELD = "oort.map.value";

    private final List<EntryListener<V>> listeners = new CopyOnWriteArrayList<>();

    public OortMap(Oort oort, String name, Factory<ConcurrentMap<String, V>> factory)
    {
        super(oort, name, factory);
    }

    public void addEntryListener(EntryListener<V> listener)
    {
        listeners.add(listener);
    }

    public void removeEntryListener(EntryListener<V> listener)
    {
        listeners.remove(listener);
    }

    /**
     * Updates a single entry of the local entity map with the given {@code key} and {@code value},
     * and then broadcasts the update to all nodes in the cluster.
     * <p/>
     * Calling this method triggers notifications {@link EntryListener}s, both on this node and on remote nodes.
     *
     * @param key   the key to associate the value to
     * @param value the value associated with the key
     * @return the previous value associated with the key, or null if no previous value was associated with the key
     * @see #removeAndShare(String)
     */
    public V putAndShare(String key, V value)
    {
        Map<String, Object> entry = new HashMap<>(2);
        entry.put(KEY_FIELD, key);
        entry.put(VALUE_FIELD, value);

        Data data = new Data(6);
        data.put(Info.VERSION_FIELD, nextVersion());
        data.put(Info.OORT_URL_FIELD, getOort().getURL());
        data.put(Info.NAME_FIELD, getName());
        data.put(Info.OBJECT_FIELD, entry);
        data.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        data.put(Info.ACTION_FIELD, ACTION_FIELD_PUT_VALUE);

        logger.debug("Sharing map put {}", data);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), data);

        return (V)data.getResult();
    }

    /**
     * Removes the given {@code key} from the local entity map,
     * and then broadcasts the removal to all nodes in the cluster.
     * <p/>
     * Calling this method triggers notifications {@link EntryListener}s, both on this node and on remote nodes.
     *
     * @param key the key to remove
     * @return the value associated with the key, or null if no value was associated with the key
     * @see #putAndShare(String, Object)
     */
    public V removeAndShare(String key)
    {
        Map<String, Object> entry = new HashMap<>(1);
        entry.put(KEY_FIELD, key);

        Data data = new Data(6);
        data.put(Info.VERSION_FIELD, nextVersion());
        data.put(Info.OORT_URL_FIELD, getOort().getURL());
        data.put(Info.NAME_FIELD, getName());
        data.put(Info.OBJECT_FIELD, entry);
        data.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        data.put(Info.ACTION_FIELD, ACTION_FIELD_REMOVE_VALUE);

        logger.debug("Sharing map remove {}", data);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), data);

        return (V)data.getResult();
    }

    /**
     * Returns the value mapped to the given key from the local entity map of this node.
     * Differently from {@link #find(String)}, only the local entity map is scanned.
     *
     * @param key the key mapped to the value to return
     * @return the value mapped to the given key, or
     *         {@code null} if the local map does not contain the given key
     * @see #find(String)
     */
    public V get(String key)
    {
        return getInfo(getOort().getURL()).getObject().get(key);
    }

    /**
     * Returns the first non-null value mapped to the given key from the entity maps of all nodes.
     * Differently from {@link #get(String)}, entity maps of all nodes are scanned.
     *
     * @param key the key mapped to the value to return
     * @return the value mapped to the given key, or
     *         {@code null} if the maps do not contain the given key
     */
    public V find(String key)
    {
        for (Info<ConcurrentMap<String, V>> info : this)
        {
            V result = info.getObject().get(key);
            if (result != null)
                return result;
        }
        return null;
    }

    /**
     * @param key the key to search
     * @return the first {@link Info} whose entity map contains the given key.
     */
    public Info<ConcurrentMap<String, V>> findInfo(String key)
    {
        for (Info<ConcurrentMap<String, V>> info : this)
        {
            if (info.getObject().get(key) != null)
                return info;
        }
        return null;
    }

    @Override
    protected void onObject(Map<String, Object> data)
    {
        if (TYPE_FIELD_ENTRY_VALUE.equals(data.get(Info.TYPE_FIELD)))
        {
            String action = (String)data.get(Info.ACTION_FIELD);
            final boolean remove = ACTION_FIELD_REMOVE_VALUE.equals(action);
            if (!ACTION_FIELD_PUT_VALUE.equals(action) && !remove)
                throw new IllegalArgumentException(action);

            String oortURL = (String)data.get(Info.OORT_URL_FIELD);
            Info<ConcurrentMap<String, V>> info = getInfo(oortURL);
            if (info != null)
            {
                // Retrieve entry
                @SuppressWarnings("unchecked")
                Map<String, Object> object = (Map<String, Object>)data.get(Info.OBJECT_FIELD);
                final String key = (String)object.get(KEY_FIELD);
                @SuppressWarnings("unchecked")
                final V value = (V)object.get(VALUE_FIELD);

                // Set the new Info
                Info<ConcurrentMap<String, V>> newInfo = new Info<>(getOort().getURL(), data);
                final ConcurrentMap<String, V> map = info.getObject();
                newInfo.put(Info.OBJECT_FIELD, map);
                final AtomicReference<V> result = new AtomicReference<>();
                MarkedReference<Info<ConcurrentMap<String, V>>> old = setInfo(newInfo, new Runnable()
                {
                    public void run()
                    {
                        if (remove)
                            result.set(map.remove(key));
                        else
                            result.set(map.put(key, value));
                    }
                });

                Entry<V> entry = new Entry<>(key, result.get(), value);

                logger.debug("{} {} map {} of {}",
                        old.isMarked() ? "Performed" : "Skipped",
                        newInfo.isLocal() ? "local" : "remote",
                        remove ? "remove" : "put",
                        entry);

                if (old.isMarked())
                {
                    if (remove)
                        notifyEntryRemoved(info, entry);
                    else
                        notifyEntryPut(info, entry);
                }

                if (data instanceof Data)
                    ((Data)data).setResult(result.get());
            }
            else
            {
                logger.debug("No info for {}", oortURL);
            }
        }
        else
        {
            super.onObject(data);
        }
    }

    private void notifyEntryPut(Info<ConcurrentMap<String, V>> info, Entry<V> entry)
    {
        for (EntryListener<V> listener : listeners)
        {
            try
            {
                listener.onPut(info, entry);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyEntryRemoved(Info<ConcurrentMap<String, V>> info, Entry<V> elements)
    {
        for (EntryListener<V> listener : listeners)
        {
            try
            {
                listener.onRemoved(info, elements);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    /**
     * Listener for entry events that update the entity map, either locally or remotely.
     *
     * @param <V> the value type
     */
    public interface EntryListener<V> extends EventListener
    {
        /**
         * Callback method invoked after an entry is put into the entity map.
         *
         * @param info the {@link Info} that was changed by the put
         * @param entry the entry values
         */
        public void onPut(Info<ConcurrentMap<String, V>> info, Entry<V> entry);

        /**
         * Callback method invoked after an entry is removed from the entity map.
         *
         * @param info the {@link Info} that was changed by the remove
         * @param entry the entry values
         */
        public void onRemoved(Info<ConcurrentMap<String, V>> info, Entry<V> entry);

        /**
         * Empty implementation of {@link EntryListener}.
         *
         * @param <V> the value type
         */
        public static class Adapter<V> implements EntryListener<V>
        {
            public void onPut(Info<ConcurrentMap<String, V>> info, Entry<V> entry)
            {
            }

            public void onRemoved(Info<ConcurrentMap<String, V>> info, Entry<V> entry)
            {
            }
        }
    }

    /**
     * A triple that holds the key, the previous value and the new value, used to notify entry updates:
     * <pre>
     * (key, oldValue, newValue)
     * </pre>
     *
     * @param <V> the value type
     */
    public static class Entry<V>
    {
        private final String key;
        private final V oldValue;
        private final V newValue;

        protected Entry(String key, V oldValue, V newValue)
        {
            this.key = key;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        /**
         * @return the key
         */
        public String getKey()
        {
            return key;
        }

        /**
         * @return the value before the change, may be null
         */
        public V getOldValue()
        {
            return oldValue;
        }

        /**
         * @return the value after the change, may be null
         */
        public V getNewValue()
        {
            return newValue;
        }

        @Override
        public String toString()
        {
            return String.format("(%s=%s->%s)", getKey(), getOldValue(), getNewValue());
        }
    }

    /**
     * An implementation of {@link Listener} that converts whole map events into {@link EntryListener} events.
     * <p />
     * For example, if an entity map:
     * <pre>
     * {
     *     key1: value1,
     *     key2: value2
     * }
     * </pre>
     * is replaced by a map:
     * <pre>
     * {
     *     key1: valueA,
     *     key3: valueB
     * }
     * </pre>
     * then this listener generates two "put" events with the following {@link Entry entries}:
     * <pre>
     * (key1, value1, valueA)
     * (key3, null, valueB)
     * </pre>
     * and one "remove" event with the following {@link Entry entry}:
     * <pre>
     * (key2, value2, null)
     * </pre>
     *
     * @param <V> the value type
     */
    public static class DeltaListener<V> implements Listener<ConcurrentMap<String, V>>
    {
        private final OortMap<V> oortMap;

        public DeltaListener(OortMap<V> oortMap)
        {
            this.oortMap = oortMap;
        }

        public void onUpdated(Info<ConcurrentMap<String, V>> oldInfo, Info<ConcurrentMap<String, V>> newInfo)
        {
            Map<String, V> oldMap = oldInfo == null ? Collections.<String, V>emptyMap() : oldInfo.getObject();
            Map<String, V> newMap = new HashMap<>(newInfo.getObject());
            for (Map.Entry<String, V> oldEntry : oldMap.entrySet())
            {
                String key = oldEntry.getKey();
                V newValue = newMap.remove(key);
                Entry<V> entry = new Entry<>(key, oldEntry.getValue(), newValue);
                if (newValue == null)
                    oortMap.notifyEntryRemoved(newInfo, entry);
                else
                    oortMap.notifyEntryPut(newInfo, entry);
            }
            for (Map.Entry<String, V> newEntry : newMap.entrySet())
            {
                Entry<V> entry = new Entry<>(newEntry.getKey(), null, newEntry.getValue());
                oortMap.notifyEntryPut(newInfo, entry);
            }
        }

        public void onRemoved(Info<ConcurrentMap<String, V>> info)
        {
            for (Map.Entry<String, V> oldEntry : info.getObject().entrySet())
            {
                Entry<V> entry = new Entry<>(oldEntry.getKey(), oldEntry.getValue(), null);
                oortMap.notifyEntryRemoved(info, entry);
            }
        }
    }
}
