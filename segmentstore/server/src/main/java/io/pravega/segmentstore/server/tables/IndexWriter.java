/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.tables;

import com.google.common.base.Preconditions;
import io.pravega.common.TimeoutTimer;
import io.pravega.segmentstore.contracts.AttributeUpdate;
import io.pravega.segmentstore.contracts.AttributeUpdateType;
import io.pravega.segmentstore.contracts.Attributes;
import io.pravega.segmentstore.contracts.BadAttributeUpdateException;
import io.pravega.segmentstore.server.DirectSegmentAccess;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Provides read-write access to a Hash Array Mapped Tree implementation over Extended Attributes.
 * See {@link IndexReader} for a description of the Index Structure.
 */
@Slf4j
class IndexWriter extends IndexReader {
    //region Members

    private final KeyHasher hasher;

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the IndexWriter class.
     *
     * @param keyHasher The {@link KeyHasher} to use for hashing keys.
     * @param executor  An Executor to use for async tasks.
     */
    IndexWriter(@NonNull KeyHasher keyHasher, ScheduledExecutorService executor) {
        super(executor);
        this.hasher = keyHasher;
    }

    //endregion

    //region Initial Table Attributes

    /**
     * Generates a set of {@link AttributeUpdate}s that set the initial Attributes on a newly create Table Segment.
     *
     * Attributes:
     * * {@link Attributes#TABLE_INDEX_OFFSET} is initialized to 0.
     *
     * @return A Collection of {@link AttributeUpdate}s.
     */
    static Collection<AttributeUpdate> generateInitialTableAttributes() {
        return Collections.singleton(new AttributeUpdate(Attributes.TABLE_INDEX_OFFSET, AttributeUpdateType.None, 0L));
    }

    //endregion

    //region Updating Table Buckets

    /**
     * Groups the given {@link BucketUpdate.KeyUpdate} instances by their associated buckets. These buckets may be partial
     * (i.e., only part of the hash matched) or a full match.
     *
     * @param keyUpdates A Collection of {@link BucketUpdate.KeyUpdate} instances to index.
     * @param segment    The Segment to read from.
     * @param timer      Timer for the operation.
     * @return A CompletableFuture that, when completed, will contain the a collection of {@link BucketUpdate}s.
     */
    CompletableFuture<Collection<BucketUpdate>> groupByBucket(Collection<BucketUpdate.KeyUpdate> keyUpdates,
                                                              DirectSegmentAccess segment, TimeoutTimer timer) {
        val updatesByHash = keyUpdates.stream()
                                      .collect(Collectors.groupingBy(k -> this.hasher.hash(k.getKey())));
        return locateBuckets(updatesByHash.keySet(), segment, timer)
                .thenApplyAsync(buckets -> {
                    val result = new HashMap<TableBucket, BucketUpdate>();
                    buckets.forEach((keyHash, bucket) -> {
                        // Add the bucket to the result and record this Key as a "new" key in it.
                        BucketUpdate bu = result.computeIfAbsent(bucket, BucketUpdate::new);
                        updatesByHash.get(keyHash).forEach(bu::withKeyUpdate);
                    });

                    return result.values();
                }, this.executor);
    }

    /**
     * Determines what Segment Attribute Updates are necessary to apply the given bucket updates and executes them
     * onto the given Segment.
     *
     * @param bucketUpdates      A Collection of {@link BucketUpdate} instances to apply. Each such instance refers to
     *                           a different {@link TableBucket} and contains the existing state and changes for it alone.
     * @param segment            A {@link DirectSegmentAccess} representing the Segment to apply the updates to.
     * @param firstIndexedOffset The first offset in the Segment that is indexed. This will be used as a conditional update
     *                           constraint (matched against the Segment's {@link Attributes#TABLE_INDEX_OFFSET}) to verify
     *                           the update will not corrupt the data (i.e., we do not overlap with another update).
     * @param lastIndexedOffset  The last offset in the Segment that is indexed. The Segment's {@link Attributes#TABLE_INDEX_OFFSET}
     *                           will be updated to this value (atomically) upon a successful completion of his call.
     * @param timeout            Timeout for the operation.
     * @return A CompletableFuture that, when completed, will contain the number of attribute updates. If the
     * operation failed, it will be failed with the appropriate exception. Notable exceptions:
     * <ul>
     * <li>{@link BadAttributeUpdateException} if the update failed due to firstIndexOffset not matching the Segment's
     * {@link Attributes#TABLE_INDEX_OFFSET}) attribute value. Such a case is retryable, but the entire bucketUpdates
     * argument must be reconstructed with the reconciled value (to prevent index corruption).
     * </ul>
     */
    CompletableFuture<Integer> updateBuckets(Collection<BucketUpdate> bucketUpdates, DirectSegmentAccess segment,
                                             long firstIndexedOffset, long lastIndexedOffset, Duration timeout) {
        List<AttributeUpdate> attributeUpdates = new ArrayList<>();

        // Process each Key in the given Map.
        // Locate the Key's Bucket, then generate necessary Attribute Updates to integrate new Keys into it.
        for (BucketUpdate bucketUpdate : bucketUpdates) {
            generateAttributeUpdates(bucketUpdate, attributeUpdates);
        }

        if (lastIndexedOffset > firstIndexedOffset) {
            // Atomically update the Table-related attributes in the Segment's metadata, once we apply these changes.
            generateTableAttributeUpdates(firstIndexedOffset, lastIndexedOffset, attributeUpdates);
        }

        if (attributeUpdates.isEmpty()) {
            // We haven't made any updates.
            log.debug("IndexWriter[{}]: FirstIdxOffset={}, LastIdxOffset={}, No Changes.", segment.getSegmentId(), firstIndexedOffset, lastIndexedOffset);
            return CompletableFuture.completedFuture(0);
        } else {
            log.debug("IndexWriter[{}]: FirstIdxOffset={}, LastIdxOffset={}, UpdateCount={}.",
                    segment.getSegmentId(), firstIndexedOffset, lastIndexedOffset, attributeUpdates.size());
            return segment.updateAttributes(attributeUpdates, timeout)
                    .thenApply(v -> attributeUpdates.size());
        }
    }

    /**
     * Generates the necessary {@link AttributeUpdate}s to index updates to the given {@link BucketUpdate}.
     *
     * Backpointer updates:
     * - Backpointers are used to resolve collisions when we have exhausted the full hash (so we cannot grow the tree
     * anymore). As such, we only calculate backpointers after we group the keys based on their full hashes.
     * - We need to handle overwritten Keys, as well as linking the new Keys.
     * - When an existing Key is overwritten, the Key after it needs to be linked to the Key before it (and its link to
     * the Key before it removed). No other links between existing Keys need to be changed.
     * - New Keys need to be linked between each other, and the first one linked to the last of existing Keys.
     *
     * TableBucket updates:
     * - We need to have the {@link TableBucket} point to the last key in updatedKeys. Backpointers will complete
     * the data structure by providing collision resolution for those remaining cases.
     *
     * @param bucketUpdate     The {@link BucketUpdate} to generate updates for.
     * @param attributeUpdates A List of {@link AttributeUpdate}s to collect into.
     */
    private void generateAttributeUpdates(BucketUpdate bucketUpdate, List<AttributeUpdate> attributeUpdates) {
        if (!bucketUpdate.hasUpdates()) {
            // Nothing to do.
            return;
        }

        // Sanity check: If we get an existing bucket (that points to a Table Entry), then it must have at least one existing
        // key, otherwise (index buckets) must not have any.
        TableBucket bucket = bucketUpdate.getBucket();
        Preconditions.checkArgument(bucket.exists() != bucketUpdate.getExistingKeys().isEmpty(),
                "Non-existing buckets must have no existing keys, while non-index Buckets must have at least one.");

        // Keep track whether the bucket was deleted (if we get at least one update, it was not).
        boolean isDeleted = true;

        // All Keys in this bucket have the same hash. If there is more than one key per such hash, then we have
        // a collision, which must be resolved (this also handles removed keys).
        generateBackpointerUpdates(bucketUpdate, attributeUpdates);

        // Backpointers help us create a linked list (of keys) for those buckets which have collisions (in reverse
        // order, by offset). At this point, we only need to figure out the highest offset of a Key in each bucket,
        // which will then become the Bucket's offset.
        val bucketOffset = bucketUpdate.getBucketOffset();
        if (bucketOffset >= 0) {
            // We have an update.
            generateBucketUpdate(bucket, bucketOffset, attributeUpdates);
        } else {
            // We have a deletion.
            generateBucketDelete(bucket, attributeUpdates);
        }
    }

    /**
     * Generates one or more {@link AttributeUpdate}s that will create or update the necessary Table Buckets entries
     * in the Segment's Extended Attributes.
     *
     * @param bucket           The Bucket to create or update.
     * @param bucketOffset The Bucket's new offset.
     * @param attributeUpdates A List of {@link AttributeUpdate}s to collect updates in.
     */
    private void generateBucketUpdate(TableBucket bucket, long bucketOffset, List<AttributeUpdate> attributeUpdates) {
        assert bucketOffset >= 0;
        attributeUpdates.add(new AttributeUpdate(bucket.getHash(), AttributeUpdateType.Replace, bucketOffset));
    }

    /**
     * Generates one or more {@link AttributeUpdate}s that will delete a {@link TableBucket}.
     *
     * @param bucket           The {@link TableBucket} to delete.
     * @param attributeUpdates A List of AttributeUpdates to collect updates in.
     */
    private void generateBucketDelete(TableBucket bucket, List<AttributeUpdate> attributeUpdates) {
        attributeUpdates.add(new AttributeUpdate(bucket.getHash(), AttributeUpdateType.Replace, Attributes.NULL_ATTRIBUTE_VALUE));
    }

    /**
     * Generates conditional {@link AttributeUpdate}s that update the values for Core Attributes representing the indexing
     * state of the Table Segment.
     *
     * @param currentOffset      The offset from which this indexing batch began. This will be checked against {@link Attributes#TABLE_INDEX_OFFSET}.
     * @param newOffset          The new offset to set for {@link Attributes#TABLE_INDEX_OFFSET}.
     * @param attributeUpdates   A List of {@link AttributeUpdate} to add the updates to.
     */
    private void generateTableAttributeUpdates(long currentOffset, long newOffset, List<AttributeUpdate> attributeUpdates) {
        // Add an Update for the TABLE_INDEX_OFFSET to indicate we have indexed everything up to this offset.
        Preconditions.checkArgument(currentOffset <= newOffset, "newOffset must be larger than existingOffset");
        attributeUpdates.add(new AttributeUpdate(Attributes.TABLE_INDEX_OFFSET, AttributeUpdateType.ReplaceIfEquals, newOffset, currentOffset));
    }

    //endregion

    //region Backpointers

    /**
     * Generates the necessary Backpointer updates for the given {@link BucketUpdate}.
     *
     * @param update           The BucketUpdate to generate Backpointers for.
     * @param attributeUpdates A List of {@link AttributeUpdate} where the updates will be collected.
     */
    private void generateBackpointerUpdates(BucketUpdate update, List<AttributeUpdate> attributeUpdates) {
        // Keep track of the previous, non-deleted Key's offset. The first one points to nothing.
        AtomicLong previousOffset = new AtomicLong(Attributes.NULL_ATTRIBUTE_VALUE);

        // Keep track of whether the previous Key has been replaced.
        AtomicBoolean previousReplaced = new AtomicBoolean(false);

        // Process all existing Keys, in order of Offsets, and either unlink them (if replaced) or update pointers as needed.
        AtomicBoolean first = new AtomicBoolean(true);
        update.getExistingKeys().stream()
              .sorted(Comparator.comparingLong(BucketUpdate.KeyInfo::getOffset))
              .forEach(keyInfo -> {
                    boolean replaced = update.isKeyUpdated(keyInfo.getKey());
                    if (replaced) {
                        // This one has been replaced or removed; delete any backpointer originating from it.
                        if (!first.get()) {
                            // ... except if this is the first one in the list, which means it doesn't have a backpointer.
                            attributeUpdates.add(generateBackpointerRemoval(keyInfo.getOffset()));
                        }

                        previousReplaced.set(true); // Record that this has been replaced.
                    } else {
                        if (previousReplaced.get()) {
                            // This one hasn't been replaced or removed, however its previous one has been.
                            // Repoint it to whatever key is now ahead of it, or remove it (if previousOffset is nothing).
                            attributeUpdates.add(generateBackpointerUpdate(keyInfo.getOffset(), previousOffset.get()));
                            previousReplaced.set(false);
                        }

                        previousOffset.set(keyInfo.getOffset()); // Record the last valid offset.
                    }

                    first.set(false);
                });

        // Process all the new Keys, in order of offsets, and add any backpointers as needed, making sure to also link them
        // to whatever surviving existing Keys we might still have.
        update.getKeyUpdates().stream()
              .filter(keyUpdate -> !keyUpdate.isDeleted())
              .sorted(Comparator.comparingLong(BucketUpdate.KeyUpdate::getOffset))
              .forEach(keyUpdate -> {
                    if (previousOffset.get() != Attributes.NULL_ATTRIBUTE_VALUE) {
                        // Only add a backpointer if we have another Key ahead of it.
                        attributeUpdates.add(generateBackpointerUpdate(keyUpdate.getOffset(), previousOffset.get()));
                    }

                    previousOffset.set(keyUpdate.getOffset());
                });
    }

    /**
     * Generates an AttributeUpdate that creates a new or updates an existing Backpointer.
     *
     * @param fromOffset The offset at which the Backpointer originates.
     * @param toOffset   The offset at which the Backpointer ends.
     */
    private AttributeUpdate generateBackpointerUpdate(long fromOffset, long toOffset) {
        return new AttributeUpdate(getBackpointerAttributeKey(fromOffset), AttributeUpdateType.Replace, toOffset);
    }

    /**
     * Generates an AttributeUpdate that removes a Backpointer, whether it exists or not.
     *
     * @param fromOffset The offset at which the Backpointer originates.
     */
    private AttributeUpdate generateBackpointerRemoval(long fromOffset) {
        return new AttributeUpdate(getBackpointerAttributeKey(fromOffset), AttributeUpdateType.Replace, Attributes.NULL_ATTRIBUTE_VALUE);
    }

    //endregion
}