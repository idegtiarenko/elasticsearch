/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.metadata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.Diff;
import org.elasticsearch.cluster.Diffable;
import org.elasticsearch.cluster.DiffableUtils;
import org.elasticsearch.cluster.DiffableUtils.MapDiff;
import org.elasticsearch.cluster.NamedDiffable;
import org.elasticsearch.cluster.NamedDiffableValueSerializer;
import org.elasticsearch.cluster.SimpleDiffable;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.coordination.CoordinationMetadata;
import org.elasticsearch.cluster.coordination.PublicationTransportHandler;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.collect.Iterators;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.VersionedNamedWriteable;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.Maps;
import org.elasticsearch.common.xcontent.ChunkedToXContent;
import org.elasticsearch.common.xcontent.ChunkedToXContentHelper;
import org.elasticsearch.common.xcontent.XContentParserUtils;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.gateway.MetadataStateFormat;
import org.elasticsearch.index.Index;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.NamedObjectNotFoundException;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.elasticsearch.common.settings.Settings.readSettingsFromStream;

/**
 * {@link Metadata} is the part of the {@link ClusterState} which persists across restarts. This persistence is XContent-based, so a
 * round-trip through XContent must be faithful in {@link XContentContext#GATEWAY} context.
 * <p>
 * The details of how this is persisted are covered in {@link org.elasticsearch.gateway.PersistedClusterStateService}.
 * </p>
 */
public class Metadata implements Diffable<Metadata>, ChunkedToXContent {

    private static final Logger logger = LogManager.getLogger(Metadata.class);

    public static final Runnable ON_NEXT_INDEX_FIND_MAPPINGS_NOOP = () -> {};
    public static final String ALL = "_all";
    public static final String UNKNOWN_CLUSTER_UUID = "_na_";
    private static final ProjectId DEFAULT_PROJECT_ID = new ProjectId("default");

    public enum XContentContext {
        /* Custom metadata should be returned as part of API call */
        API,

        /* Custom metadata should be stored as part of the persistent cluster state */
        GATEWAY,

        /* Custom metadata should be stored as part of a snapshot */
        SNAPSHOT
    }

    /**
     * Indicates that this custom metadata will be returned as part of an API call but will not be persisted
     */
    public static EnumSet<XContentContext> API_ONLY = EnumSet.of(XContentContext.API);

    /**
     * Indicates that this custom metadata will be returned as part of an API call and will be persisted between
     * node restarts, but will not be a part of a snapshot global state
     */
    public static EnumSet<XContentContext> API_AND_GATEWAY = EnumSet.of(XContentContext.API, XContentContext.GATEWAY);

    /**
     * Indicates that this custom metadata will be returned as part of an API call and stored as a part of
     * a snapshot global state, but will not be persisted between node restarts
     */
    public static EnumSet<XContentContext> API_AND_SNAPSHOT = EnumSet.of(XContentContext.API, XContentContext.SNAPSHOT);

    /**
     * Indicates that this custom metadata will be returned as part of an API call, stored as a part of
     * a snapshot global state, and will be persisted between node restarts
     */
    public static EnumSet<XContentContext> ALL_CONTEXTS = EnumSet.allOf(XContentContext.class);

    public interface MetadataCustom<T> extends NamedDiffable<T>, ChunkedToXContent {

        EnumSet<XContentContext> context();

        /**
         * @return true if this custom could be restored from snapshot
         */
        default boolean isRestorable() {
            return context().contains(XContentContext.SNAPSHOT);
        }
    }

    /**
     * Cluster-level custom metadata that persists (via XContent) across restarts.
     * The deserialization method for each implementation must be registered with the {@link NamedXContentRegistry}.
     */
    public interface ClusterCustom extends MetadataCustom<ClusterCustom> {}

    /**
     * Project-level custom metadata that persists (via XContent) across restarts.
     * The deserialization method for each implementation must be registered with the {@link NamedXContentRegistry}.
     */
    public interface ProjectCustom extends MetadataCustom<ProjectCustom> {}

    public static final Setting<Boolean> SETTING_READ_ONLY_SETTING = Setting.boolSetting(
        "cluster.blocks.read_only",
        false,
        Property.Dynamic,
        Property.NodeScope
    );

    public static final ClusterBlock CLUSTER_READ_ONLY_BLOCK = new ClusterBlock(
        6,
        "cluster read-only (api)",
        false,
        false,
        false,
        RestStatus.FORBIDDEN,
        EnumSet.of(ClusterBlockLevel.WRITE, ClusterBlockLevel.METADATA_WRITE)
    );

    public static final Setting<Boolean> SETTING_READ_ONLY_ALLOW_DELETE_SETTING = Setting.boolSetting(
        "cluster.blocks.read_only_allow_delete",
        false,
        Property.Dynamic,
        Property.NodeScope
    );

    public static final ClusterBlock CLUSTER_READ_ONLY_ALLOW_DELETE_BLOCK = new ClusterBlock(
        13,
        "cluster read-only / allow delete (api)",
        false,
        false,
        true,
        RestStatus.FORBIDDEN,
        EnumSet.of(ClusterBlockLevel.WRITE, ClusterBlockLevel.METADATA_WRITE)
    );

    public static final Metadata EMPTY_METADATA = builder().build();

    public static final String CONTEXT_MODE_PARAM = "context_mode";

    public static final String CONTEXT_MODE_SNAPSHOT = XContentContext.SNAPSHOT.toString();

    public static final String CONTEXT_MODE_GATEWAY = XContentContext.GATEWAY.toString();

    public static final String CONTEXT_MODE_API = XContentContext.API.toString();

    public static final String DEDUPLICATED_MAPPINGS_PARAM = "deduplicated_mappings";
    public static final String GLOBAL_STATE_FILE_PREFIX = "global-";

    private static final NamedDiffableValueSerializer<ClusterCustom> CLUSTER_CUSTOM_VALUE_SERIALIZER = new NamedDiffableValueSerializer<>(
        ClusterCustom.class
    );
    private static final NamedDiffableValueSerializer<Metadata.ProjectCustom> PROJECT_CUSTOM_VALUE_SERIALIZER =
        new NamedDiffableValueSerializer<>(Metadata.ProjectCustom.class);

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static final NamedDiffableValueSerializer BWC_CUSTOM_VALUE_SERIALIZER = new NamedDiffableValueSerializer(MetadataCustom.class) {
        @Override
        public MetadataCustom read(StreamInput in, String key) throws IOException {
            final Set<String> clusterScopedNames = in.namedWriteableRegistry().getReaders(ClusterCustom.class).keySet();
            final Set<String> projectScopedNames = in.namedWriteableRegistry().getReaders(ProjectCustom.class).keySet();
            if (clusterScopedNames.contains(key)) {
                return in.readNamedWriteable(ClusterCustom.class, key);
            } else if (projectScopedNames.contains(key)) {
                return in.readNamedWriteable(ProjectCustom.class, key);
            } else {
                throw new IllegalArgumentException("Unknown custom name [" + key + "]");
            }
        }
    };

    private final String clusterUUID;
    private final boolean clusterUUIDCommitted;
    private final long version;

    private final CoordinationMetadata coordinationMetadata;
    private final Map<ProjectId, ProjectMetadata> projectMetadata;

    private final Settings transientSettings;
    private final Settings persistentSettings;
    private final Settings settings;
    private final DiffableStringMap hashesOfConsistentSettings;
    private final ImmutableOpenMap<String, Metadata.ClusterCustom> customs;
    private final Map<String, ReservedStateMetadata> reservedStateMetadata;

    private Metadata(
        String clusterUUID,
        boolean clusterUUIDCommitted,
        long version,
        CoordinationMetadata coordinationMetadata,
        Map<ProjectId, ProjectMetadata> projectMetadata,
        Settings transientSettings,
        Settings persistentSettings,
        Settings settings,
        DiffableStringMap hashesOfConsistentSettings,
        ImmutableOpenMap<String, ClusterCustom> customs,
        Map<String, ReservedStateMetadata> reservedStateMetadata
    ) {
        this.clusterUUID = clusterUUID;
        this.clusterUUIDCommitted = clusterUUIDCommitted;
        this.version = version;
        this.coordinationMetadata = coordinationMetadata;
        this.projectMetadata = projectMetadata;
        this.transientSettings = transientSettings;
        this.persistentSettings = persistentSettings;
        this.settings = settings;
        this.hashesOfConsistentSettings = hashesOfConsistentSettings;
        this.customs = customs;
        this.reservedStateMetadata = reservedStateMetadata;
    }

    private ProjectMetadata getSingleProject() {
        if (projectMetadata.isEmpty()) {
            throw new UnsupportedOperationException("There are no projects");
        } else if (projectMetadata.size() != 1) {
            throw new UnsupportedOperationException("There are multiple projects " + projectMetadata.keySet());
        }
        final ProjectMetadata defaultProject = projectMetadata.get(DEFAULT_PROJECT_ID);
        if (defaultProject == null) {
            throw new UnsupportedOperationException(
                "There is 1 project, but it has id " + projectMetadata.keySet() + " rather than " + DEFAULT_PROJECT_ID
            );
        }
        return defaultProject;
    }

    public Metadata withIncrementedVersion() {
        return new Metadata(
            clusterUUID,
            clusterUUIDCommitted,
            version + 1,
            coordinationMetadata,
            projectMetadata,
            transientSettings,
            persistentSettings,
            settings,
            hashesOfConsistentSettings,
            customs,
            reservedStateMetadata
        );
    }

    /**
     * Given an index and lifecycle state, returns a metadata where the lifecycle state will be
     * associated with the given index.
     *
     * The passed-in index must already be present in the cluster state, this method cannot
     * be used to add an index.
     *
     * @param index A non-null index
     * @param lifecycleState A non-null lifecycle execution state
     * @return a <code>Metadata</code> instance where the index has the provided lifecycle state
     */
    public Metadata withLifecycleState(Index index, LifecycleExecutionState lifecycleState) {
        return updateSingleProject(project -> project.withLifecycleState(index, lifecycleState));
    }

    public Metadata withIndexSettingsUpdates(Map<Index, Settings> updates) {
        return updateSingleProject(project -> project.withIndexSettingsUpdates(updates));
    }

    public Metadata withCoordinationMetadata(CoordinationMetadata coordinationMetadata) {
        return new Metadata(
            clusterUUID,
            clusterUUIDCommitted,
            version,
            coordinationMetadata,
            projectMetadata,
            transientSettings,
            persistentSettings,
            settings,
            hashesOfConsistentSettings,
            customs,
            reservedStateMetadata
        );
    }

    public Metadata withLastCommittedValues(
        boolean clusterUUIDCommitted,
        CoordinationMetadata.VotingConfiguration lastCommittedConfiguration
    ) {
        if (clusterUUIDCommitted == this.clusterUUIDCommitted
            && lastCommittedConfiguration.equals(this.coordinationMetadata.getLastCommittedConfiguration())) {
            return this;
        }
        return new Metadata(
            clusterUUID,
            clusterUUIDCommitted,
            version,
            CoordinationMetadata.builder(coordinationMetadata).lastCommittedConfiguration(lastCommittedConfiguration).build(),
            projectMetadata,
            transientSettings,
            persistentSettings,
            settings,
            hashesOfConsistentSettings,
            customs,
            reservedStateMetadata
        );
    }

    /**
     * Creates a copy of this instance updated with the given {@link IndexMetadata} that must only contain changes to primary terms
     * and in-sync allocation ids relative to the existing entries. This method is only used by
     * {@link org.elasticsearch.cluster.routing.allocation.IndexMetadataUpdater#applyChanges(Metadata, RoutingTable, TransportVersion)}.
     * @param updates map of index name to {@link IndexMetadata}.
     * @return updated metadata instance
     */
    public Metadata withAllocationAndTermUpdatesOnly(Map<String, IndexMetadata> updates) {
        return updateSingleProject(project -> project.withAllocationAndTermUpdatesOnly(updates));
    }

    /**
     * Creates a copy of this instance with the given {@code index} added.
     * @param index index to add
     * @return copy with added index
     */
    public Metadata withAddedIndex(IndexMetadata index) {
        return updateSingleProject(project -> project.withAddedIndex(index));
    }

    private Metadata updateSingleProject(Function<ProjectMetadata, ProjectMetadata> update) {
        if (projectMetadata.size() == 1) {
            var entry = this.projectMetadata.entrySet().iterator().next();
            var newProject = update.apply(entry.getValue());
            return newProject == entry.getValue()
                ? this
                : new Metadata(
                    clusterUUID,
                    clusterUUIDCommitted,
                    version,
                    coordinationMetadata,
                    Map.of(entry.getKey(), newProject),
                    transientSettings,
                    persistentSettings,
                    settings,
                    hashesOfConsistentSettings,
                    customs,
                    reservedStateMetadata
                );
        } else {
            throw new UnsupportedOperationException("There are multiple projects " + projectMetadata.keySet());
        }
    }

    public long version() {
        return this.version;
    }

    public String clusterUUID() {
        return this.clusterUUID;
    }

    /**
     * Whether the current node with the given cluster state is locked into the cluster with the UUID returned by {@link #clusterUUID()},
     * meaning that it will not accept any cluster state with a different clusterUUID.
     */
    public boolean clusterUUIDCommitted() {
        return this.clusterUUIDCommitted;
    }

    /**
     * Returns the merged transient and persistent settings.
     */
    public Settings settings() {
        return this.settings;
    }

    public Settings transientSettings() {
        return this.transientSettings;
    }

    public Settings persistentSettings() {
        return this.persistentSettings;
    }

    public Map<String, String> hashesOfConsistentSettings() {
        return this.hashesOfConsistentSettings;
    }

    public CoordinationMetadata coordinationMetadata() {
        return this.coordinationMetadata;
    }

    public ProjectMetadata getProject() {
        return getSingleProject();
    }

    public ProjectMetadata getProject(ProjectId projectId) {
        ProjectMetadata metadata = projectMetadata.get(projectId);
        assert metadata != null : "Project " + projectId.id() + " not found in " + projectMetadata.keySet();
        return metadata;
    }

    public NodesShutdownMetadata nodeShutdowns() {
        return custom(NodesShutdownMetadata.TYPE, NodesShutdownMetadata.EMPTY);
    }

    public ImmutableOpenMap<String, ClusterCustom> customs() {
        return this.customs;
    }

    /**
     * Returns the full {@link ReservedStateMetadata} Map for all
     * reserved state namespaces.
     * @return a map of namespace to {@link ReservedStateMetadata}
     */
    public Map<String, ReservedStateMetadata> reservedStateMetadata() {
        return this.reservedStateMetadata;
    }

    @SuppressWarnings("unchecked")
    public <T extends ClusterCustom> T custom(String type) {
        return (T) customs.get(type);
    }

    @SuppressWarnings("unchecked")
    public <T extends ClusterCustom> T custom(String type, T defaultValue) {
        return (T) customs.getOrDefault(type, defaultValue);
    }

    public static boolean isGlobalStateEquals(Metadata metadata1, Metadata metadata2) {
        if (metadata1.coordinationMetadata.equals(metadata2.coordinationMetadata) == false) {
            return false;
        }
        if (metadata1.persistentSettings.equals(metadata2.persistentSettings) == false) {
            return false;
        }
        if (metadata1.hashesOfConsistentSettings.equals(metadata2.hashesOfConsistentSettings) == false) {
            return false;
        }
        if (metadata1.getSingleProject().templates().equals(metadata2.getSingleProject().templates()) == false) {
            return false;
        }
        if (metadata1.clusterUUID.equals(metadata2.clusterUUID) == false) {
            return false;
        }
        if (metadata1.clusterUUIDCommitted != metadata2.clusterUUIDCommitted) {
            return false;
        }
        if (customsEqual(metadata1.customs, metadata2.customs) == false) {
            return false;
        }
        if (customsEqual(metadata1.getSingleProject().customs(), metadata2.getSingleProject().customs()) == false) {
            return false;
        }
        if (Objects.equals(metadata1.reservedStateMetadata, metadata2.reservedStateMetadata) == false) {
            return false;
        }
        return true;
    }

    private static <C extends MetadataCustom<C>> boolean customsEqual(
        ImmutableOpenMap<String, C> customs1,
        ImmutableOpenMap<String, C> customs2
    ) {
        // Check if any persistent metadata needs to be saved
        int customCount1 = 0;
        for (Map.Entry<String, C> cursor : customs1.entrySet()) {
            if (cursor.getValue().context().contains(XContentContext.GATEWAY)) {
                if (cursor.getValue().equals(customs2.get(cursor.getKey())) == false) {
                    return false;
                }
                customCount1++;
            }
        }
        int customCount2 = 0;
        for (C custom : customs2.values()) {
            if (custom.context().contains(XContentContext.GATEWAY)) {
                customCount2++;
            }
        }
        return customCount1 == customCount2;
    }

    @Override
    public Diff<Metadata> diff(Metadata previousState) {
        return new MetadataDiff(previousState, this);
    }

    public static Diff<Metadata> readDiffFrom(StreamInput in) throws IOException {
        if (in.getTransportVersion().onOrAfter(MetadataDiff.NOOP_METADATA_DIFF_VERSION) && in.readBoolean()) {
            return SimpleDiffable.empty();
        }
        return new MetadataDiff(in);
    }

    public static Metadata fromXContent(XContentParser parser) throws IOException {
        return Builder.fromXContent(parser);
    }

    @Override
    public Iterator<? extends ToXContent> toXContentChunked(ToXContent.Params p) {
        XContentContext context = XContentContext.valueOf(p.param(CONTEXT_MODE_PARAM, CONTEXT_MODE_API));
        final Iterator<? extends ToXContent> start = context == XContentContext.API
            ? ChunkedToXContentHelper.startObject("metadata")
            : Iterators.single((builder, params) -> builder.startObject("meta-data").field("version", version()));

        final Iterator<? extends ToXContent> persistentSettings = context != XContentContext.API && persistentSettings().isEmpty() == false
            ? Iterators.single((builder, params) -> {
                builder.startObject("settings");
                persistentSettings().toXContent(builder, new ToXContent.MapParams(Collections.singletonMap("flat_settings", "true")));
                return builder.endObject();
            })
            : Collections.emptyIterator();

        return Iterators.concat(start, Iterators.single((builder, params) -> {
            builder.field("cluster_uuid", clusterUUID);
            builder.field("cluster_uuid_committed", clusterUUIDCommitted);
            builder.startObject("cluster_coordination");
            coordinationMetadata().toXContent(builder, params);
            return builder.endObject();
        }),
            persistentSettings,
            getSingleProject().toXContentChunked(p),
            Iterators.flatMap(
                customs.entrySet().iterator(),
                entry -> entry.getValue().context().contains(context)
                    ? ChunkedToXContentHelper.wrapWithObject(entry.getKey(), entry.getValue().toXContentChunked(p))
                    : Collections.emptyIterator()
            ),
            ChunkedToXContentHelper.wrapWithObject("reserved_state", reservedStateMetadata().values().iterator()),
            ChunkedToXContentHelper.endObject()
        );
    }

    private static class MetadataDiff implements Diff<Metadata> {

        private static final TransportVersion NOOP_METADATA_DIFF_VERSION = TransportVersions.V_8_5_0;
        private static final TransportVersion NOOP_METADATA_DIFF_SAFE_VERSION =
            PublicationTransportHandler.INCLUDES_LAST_COMMITTED_DATA_VERSION;

        private final long version;
        private final String clusterUUID;
        private final boolean clusterUUIDCommitted;
        private final CoordinationMetadata coordinationMetadata;
        private final Settings transientSettings;
        private final Settings persistentSettings;
        private final Diff<DiffableStringMap> hashesOfConsistentSettings;
        private final ProjectMetadata.ProjectMetadataDiff projectMetadata;
        private final MapDiff<String, ClusterCustom, ImmutableOpenMap<String, ClusterCustom>> clusterCustoms;
        private final Diff<Map<String, ReservedStateMetadata>> reservedStateMetadata;

        /**
         * true if this diff is a noop because before and after were the same instance
         */
        private final boolean empty;

        MetadataDiff(Metadata before, Metadata after) {
            this.empty = before == after;
            clusterUUID = after.clusterUUID;
            clusterUUIDCommitted = after.clusterUUIDCommitted;
            version = after.version;
            coordinationMetadata = after.coordinationMetadata;
            transientSettings = after.transientSettings;
            persistentSettings = after.persistentSettings;
            projectMetadata = after.getSingleProject().diff(before.getSingleProject());
            if (empty) {
                hashesOfConsistentSettings = DiffableStringMap.DiffableStringMapDiff.EMPTY;
                clusterCustoms = DiffableUtils.emptyDiff();
                reservedStateMetadata = DiffableUtils.emptyDiff();
            } else {
                hashesOfConsistentSettings = after.hashesOfConsistentSettings.diff(before.hashesOfConsistentSettings);
                clusterCustoms = DiffableUtils.diff(
                    before.customs,
                    after.customs,
                    DiffableUtils.getStringKeySerializer(),
                    CLUSTER_CUSTOM_VALUE_SERIALIZER
                );
                reservedStateMetadata = DiffableUtils.diff(
                    before.reservedStateMetadata,
                    after.reservedStateMetadata,
                    DiffableUtils.getStringKeySerializer()
                );
            }
        }

        private static final DiffableUtils.DiffableValueReader<String, IndexMetadata> INDEX_METADATA_DIFF_VALUE_READER =
            new DiffableUtils.DiffableValueReader<>(IndexMetadata::readFrom, IndexMetadata::readDiffFrom);
        private static final DiffableUtils.DiffableValueReader<String, IndexTemplateMetadata> TEMPLATES_DIFF_VALUE_READER =
            new DiffableUtils.DiffableValueReader<>(IndexTemplateMetadata::readFrom, IndexTemplateMetadata::readDiffFrom);
        private static final DiffableUtils.DiffableValueReader<String, ReservedStateMetadata> RESERVED_DIFF_VALUE_READER =
            new DiffableUtils.DiffableValueReader<>(ReservedStateMetadata::readFrom, ReservedStateMetadata::readDiffFrom);

        private MetadataDiff(StreamInput in) throws IOException {
            empty = false;
            clusterUUID = in.readString();
            clusterUUIDCommitted = in.readBoolean();
            version = in.readLong();
            coordinationMetadata = new CoordinationMetadata(in);
            transientSettings = Settings.readSettingsFromStream(in);
            persistentSettings = Settings.readSettingsFromStream(in);
            if (in.getTransportVersion().onOrAfter(TransportVersions.V_7_3_0)) {
                hashesOfConsistentSettings = DiffableStringMap.readDiffFrom(in);
            } else {
                hashesOfConsistentSettings = DiffableStringMap.DiffableStringMapDiff.EMPTY;
            }
            if (in.getTransportVersion().before(TransportVersions.MULTI_PROJECT)) {
                var indices = DiffableUtils.readImmutableOpenMapDiff(
                    in,
                    DiffableUtils.getStringKeySerializer(),
                    INDEX_METADATA_DIFF_VALUE_READER
                );
                var templates = DiffableUtils.readImmutableOpenMapDiff(
                    in,
                    DiffableUtils.getStringKeySerializer(),
                    TEMPLATES_DIFF_VALUE_READER
                );
                var bwcCustoms = readBwcCustoms(in);
                clusterCustoms = bwcCustoms.v1();
                var projectCustoms = bwcCustoms.v2();
                projectMetadata = new ProjectMetadata.ProjectMetadataDiff(indices, templates, projectCustoms);
            } else {
                clusterCustoms = DiffableUtils.readImmutableOpenMapDiff(
                    in,
                    DiffableUtils.getStringKeySerializer(),
                    CLUSTER_CUSTOM_VALUE_SERIALIZER
                );
                projectMetadata = new ProjectMetadata.ProjectMetadataDiff(in);
            }
            if (in.getTransportVersion().onOrAfter(TransportVersions.V_8_4_0)) {
                reservedStateMetadata = DiffableUtils.readJdkMapDiff(
                    in,
                    DiffableUtils.getStringKeySerializer(),
                    RESERVED_DIFF_VALUE_READER
                );
            } else {
                reservedStateMetadata = DiffableUtils.emptyDiff();
            }
        }

        @SuppressWarnings("unchecked")
        private static
            Tuple<
                MapDiff<String, ClusterCustom, ImmutableOpenMap<String, ClusterCustom>>,
                MapDiff<String, ProjectCustom, ImmutableOpenMap<String, ProjectCustom>>>
            readBwcCustoms(StreamInput in) throws IOException {
            MapDiff<String, MetadataCustom<?>, ImmutableOpenMap<String, MetadataCustom<?>>> customs = DiffableUtils
                .readImmutableOpenMapDiff(in, DiffableUtils.getStringKeySerializer(), BWC_CUSTOM_VALUE_SERIALIZER);
            return DiffableUtils.split(
                customs,
                in.namedWriteableRegistry().getReaders(ClusterCustom.class).keySet(),
                CLUSTER_CUSTOM_VALUE_SERIALIZER,
                in.namedWriteableRegistry().getReaders(ProjectCustom.class).keySet(),
                PROJECT_CUSTOM_VALUE_SERIALIZER
            );
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            if (out.getTransportVersion().onOrAfter(NOOP_METADATA_DIFF_SAFE_VERSION)) {
                out.writeBoolean(empty);
                if (empty) {
                    // noop diff
                    return;
                }
            } else if (out.getTransportVersion().onOrAfter(NOOP_METADATA_DIFF_VERSION)) {
                // noops are not safe with these versions, see #92259
                out.writeBoolean(false);
            }
            out.writeString(clusterUUID);
            out.writeBoolean(clusterUUIDCommitted);
            out.writeLong(version);
            coordinationMetadata.writeTo(out);
            transientSettings.writeTo(out);
            persistentSettings.writeTo(out);
            if (out.getTransportVersion().onOrAfter(TransportVersions.V_7_3_0)) {
                hashesOfConsistentSettings.writeTo(out);
            }
            if (out.getTransportVersion().before(TransportVersions.MULTI_PROJECT)) {
                projectMetadata.indices.writeTo(out);
                projectMetadata.templates.writeTo(out);
                buildUnifiedCustomDiff().writeTo(out);
            } else {
                clusterCustoms.writeTo(out);
                projectMetadata.writeTo(out);
            }
            if (out.getTransportVersion().onOrAfter(TransportVersions.V_8_4_0)) {
                reservedStateMetadata.writeTo(out);
            }
        }

        @SuppressWarnings("unchecked")
        private Diff<ImmutableOpenMap<String, ?>> buildUnifiedCustomDiff() {
            return DiffableUtils.merge(
                clusterCustoms,
                projectMetadata.customs,
                DiffableUtils.getStringKeySerializer(),
                BWC_CUSTOM_VALUE_SERIALIZER
            );
        }

        @Override
        public Metadata apply(Metadata part) {
            if (empty) {
                return part;
            }
            // create builder from existing mappings hashes so we don't change existing index metadata instances when deduplicating
            // mappings in the builder
            Builder builder = new Builder();
            builder.clusterUUID(clusterUUID);
            builder.clusterUUIDCommitted(clusterUUIDCommitted);
            builder.version(version);
            builder.coordinationMetadata(coordinationMetadata);
            builder.transientSettings(transientSettings);
            builder.persistentSettings(persistentSettings);
            builder.hashesOfConsistentSettings(hashesOfConsistentSettings.apply(part.hashesOfConsistentSettings));
            builder.put(projectMetadata.apply(part.getSingleProject()));
            builder.customs(clusterCustoms.apply(part.customs));
            builder.put(reservedStateMetadata.apply(part.reservedStateMetadata));
            return builder.build(true);
        }
    }

    public static final TransportVersion MAPPINGS_AS_HASH_VERSION = TransportVersions.V_8_1_0;

    public static Metadata readFrom(StreamInput in) throws IOException {
        Builder builder = new Builder();
        builder.version(in.readLong());
        builder.clusterUUID(in.readString());
        builder.clusterUUIDCommitted(in.readBoolean());
        builder.coordinationMetadata(new CoordinationMetadata(in));
        builder.transientSettings(readSettingsFromStream(in));
        builder.persistentSettings(readSettingsFromStream(in));
        if (in.getTransportVersion().onOrAfter(TransportVersions.V_7_3_0)) {
            builder.hashesOfConsistentSettings(DiffableStringMap.readFrom(in));
        }
        if (in.getTransportVersion().before(TransportVersions.MULTI_PROJECT)) {
            final Function<String, MappingMetadata> mappingLookup;
            if (in.getTransportVersion().onOrAfter(MAPPINGS_AS_HASH_VERSION)) {
                final Map<String, MappingMetadata> mappingMetadataMap = in.readMapValues(MappingMetadata::new, MappingMetadata::getSha256);
                if (mappingMetadataMap.isEmpty() == false) {
                    mappingLookup = mappingMetadataMap::get;
                } else {
                    mappingLookup = null;
                }
            } else {
                mappingLookup = null;
            }
            int size = in.readVInt();
            for (int i = 0; i < size; i++) {
                builder.put(IndexMetadata.readFrom(in, mappingLookup), false);
            }
            size = in.readVInt();
            for (int i = 0; i < size; i++) {
                builder.put(IndexTemplateMetadata.readFrom(in));
            }
            readBwcCustoms(in, builder);
        } else {
            readClusterCustoms(in, builder);
            builder.put(ProjectMetadata.readFrom(in));
        }
        if (in.getTransportVersion().onOrAfter(TransportVersions.V_8_4_0)) {
            int reservedStateSize = in.readVInt();
            for (int i = 0; i < reservedStateSize; i++) {
                builder.put(ReservedStateMetadata.readFrom(in));
            }
        }
        return builder.build();
    }

    private static void readBwcCustoms(StreamInput in, Builder builder) throws IOException {
        final Set<String> clusterScopedNames = in.namedWriteableRegistry().getReaders(ClusterCustom.class).keySet();
        final Set<String> projectScopedNames = in.namedWriteableRegistry().getReaders(ProjectCustom.class).keySet();
        final int count = in.readVInt();
        for (int i = 0; i < count; i++) {
            final String name = in.readString();
            if (clusterScopedNames.contains(name)) {
                final ClusterCustom custom = in.readNamedWriteable(ClusterCustom.class, name);
                builder.putCustom(custom.getWriteableName(), custom);
            } else if (projectScopedNames.contains(name)) {
                final ProjectCustom custom = in.readNamedWriteable(ProjectCustom.class, name);
                builder.putProjectCustom(custom.getWriteableName(), custom);
            } else {
                throw new IllegalArgumentException("Unknown custom name [" + name + "]");
            }
        }
    }

    private static void readClusterCustoms(StreamInput in, Builder builder) throws IOException {
        Set<String> clusterScopedNames = in.namedWriteableRegistry().getReaders(ClusterCustom.class).keySet();
        int count = in.readVInt();
        for (int i = 0; i < count; i++) {
            final String name = in.readString();
            if (clusterScopedNames.contains(name)) {
                ClusterCustom custom = in.readNamedWriteable(ClusterCustom.class, name);
                builder.putCustom(custom.getWriteableName(), custom);
            } else {
                throw new IllegalArgumentException("Unknown cluster custom name [" + name + "]");
            }
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeLong(version);
        out.writeString(clusterUUID);
        out.writeBoolean(clusterUUIDCommitted);
        coordinationMetadata.writeTo(out);
        transientSettings.writeTo(out);
        persistentSettings.writeTo(out);
        if (out.getTransportVersion().onOrAfter(TransportVersions.V_7_3_0)) {
            hashesOfConsistentSettings.writeTo(out);
        }
        if (out.getTransportVersion().before(TransportVersions.MULTI_PROJECT)) {
            ProjectMetadata singleProject = getSingleProject();
            // Starting in #MAPPINGS_AS_HASH_VERSION we write the mapping metadata first and then write the indices without metadata so that
            // we avoid writing duplicate mappings twice
            if (out.getTransportVersion().onOrAfter(MAPPINGS_AS_HASH_VERSION)) {
                out.writeMapValues(singleProject.getMappingsByHash());
            }
            out.writeVInt(singleProject.size());
            final boolean writeMappingsHash = out.getTransportVersion().onOrAfter(MAPPINGS_AS_HASH_VERSION);
            for (IndexMetadata indexMetadata : singleProject) {
                indexMetadata.writeTo(out, writeMappingsHash);
            }
            out.writeCollection(singleProject.templates().values());
            // It would be nice to do this as flattening iterable (rather than allocation a whole new list), but flattening
            // Iterable<? extends VersionNamedWriteable> into Iterable<VersionNamedWriteable> is messy, so we can fix that later
            List<VersionedNamedWriteable> merge = new ArrayList<>(customs.size() + singleProject.customs().size());
            merge.addAll(customs.values());
            merge.addAll(singleProject.customs().values());
            VersionedNamedWriteable.writeVersionedWriteables(out, merge);
        } else {
            VersionedNamedWriteable.writeVersionedWriteables(out, customs.values());
            getSingleProject().writeTo(out);
        }
        if (out.getTransportVersion().onOrAfter(TransportVersions.V_8_4_0)) {
            out.writeCollection(reservedStateMetadata.values());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(Metadata metadata) {
        return new Builder(metadata);
    }

    public Metadata copyAndUpdate(Consumer<Builder> updater) {
        var builder = builder(this);
        updater.accept(builder);
        return builder.build();
    }

    public static class Builder {

        private String clusterUUID;
        private boolean clusterUUIDCommitted;
        private long version;

        private CoordinationMetadata coordinationMetadata = CoordinationMetadata.EMPTY_METADATA;
        private Settings transientSettings = Settings.EMPTY;
        private Settings persistentSettings = Settings.EMPTY;
        private DiffableStringMap hashesOfConsistentSettings = DiffableStringMap.EMPTY;

        private final ImmutableOpenMap.Builder<String, ClusterCustom> customs;
        private final Map<ProjectId, ProjectMetadata.Builder> projectMetadata;

        private final Map<String, ReservedStateMetadata> reservedStateMetadata;

        @SuppressWarnings("this-escape")
        public Builder() {
            clusterUUID = UNKNOWN_CLUSTER_UUID;
            customs = ImmutableOpenMap.builder();
            projectMetadata = new HashMap<>();
            reservedStateMetadata = new HashMap<>();
        }

        Builder(Metadata metadata) {
            this.clusterUUID = metadata.clusterUUID;
            this.clusterUUIDCommitted = metadata.clusterUUIDCommitted;
            this.coordinationMetadata = metadata.coordinationMetadata;
            this.transientSettings = metadata.transientSettings;
            this.persistentSettings = metadata.persistentSettings;
            this.hashesOfConsistentSettings = metadata.hashesOfConsistentSettings;
            this.version = metadata.version;
            this.customs = ImmutableOpenMap.builder(metadata.customs);
            this.projectMetadata = Maps.transformValues(metadata.projectMetadata, ProjectMetadata::builder);
            this.reservedStateMetadata = new HashMap<>(metadata.reservedStateMetadata);
        }

        private ProjectMetadata.Builder getSingleProject() {
            if (projectMetadata.isEmpty()) {
                createDefaultProject();
            } else if (projectMetadata.size() != 1) {
                throw new UnsupportedOperationException("There are multiple projects " + projectMetadata.keySet());
            }
            return projectMetadata.values().iterator().next();
        }

        Builder put(ProjectMetadata projectMetadata) {
            return put(ProjectMetadata.builder(projectMetadata));
        }

        public Builder put(ProjectMetadata.Builder projectMetadata) {
            // TODO: tighten this up when we remove all the methods delegating to ProjectMetadata.Builder
            this.projectMetadata.clear();
            return put(DEFAULT_PROJECT_ID, projectMetadata);
        }

        public Builder put(ProjectId projectId, ProjectMetadata.Builder projectMetadata) {
            this.projectMetadata.put(projectId, projectMetadata);
            return this;
        }

        public Builder put(IndexMetadata.Builder indexMetadataBuilder) {
            getSingleProject().put(indexMetadataBuilder);
            return this;
        }

        public Builder put(IndexMetadata indexMetadata, boolean incrementVersion) {
            getSingleProject().put(indexMetadata, incrementVersion);
            return this;
        }

        public IndexMetadata get(String index) {
            return getSingleProject().get(index);
        }

        public IndexMetadata getSafe(Index index) {
            return getSingleProject().getSafe(index);
        }

        public Builder remove(String index) {
            getSingleProject().remove(index);
            return this;
        }

        public Builder removeAllIndices() {
            getSingleProject().removeAllIndices();
            return this;
        }

        public Builder indices(Map<String, IndexMetadata> indices) {
            getSingleProject().indices(indices);
            return this;
        }

        public Builder put(IndexTemplateMetadata.Builder template) {
            getSingleProject().put(template);
            return this;
        }

        public Builder put(IndexTemplateMetadata template) {
            getSingleProject().put(template);
            return this;
        }

        public Builder removeTemplate(String templateName) {
            getSingleProject().removeTemplate(templateName);
            return this;
        }

        public Builder templates(Map<String, IndexTemplateMetadata> templates) {
            getSingleProject().templates(templates);
            return this;
        }

        public Builder put(String name, ComponentTemplate componentTemplate) {
            getSingleProject().put(name, componentTemplate);
            return this;
        }

        public Builder removeComponentTemplate(String name) {
            getSingleProject().removeComponentTemplate(name);
            return this;
        }

        public Builder componentTemplates(Map<String, ComponentTemplate> componentTemplates) {
            getSingleProject().componentTemplates(componentTemplates);
            return this;
        }

        public Builder indexTemplates(Map<String, ComposableIndexTemplate> indexTemplates) {
            getSingleProject().indexTemplates(indexTemplates);
            return this;
        }

        public Builder put(String name, ComposableIndexTemplate indexTemplate) {
            getSingleProject().put(name, indexTemplate);
            return this;
        }

        public Builder removeIndexTemplate(String name) {
            getSingleProject().removeIndexTemplate(name);
            return this;
        }

        public DataStream dataStream(String dataStreamName) {
            return getSingleProject().dataStream(dataStreamName);
        }

        public Builder dataStreams(Map<String, DataStream> dataStreams, Map<String, DataStreamAlias> dataStreamAliases) {
            getSingleProject().dataStreams(dataStreams, dataStreamAliases);
            return this;
        }

        public Builder put(DataStream dataStream) {
            getSingleProject().put(dataStream);
            return this;
        }

        public DataStreamMetadata dataStreamMetadata() {
            return getSingleProject().dataStreamMetadata();
        }

        public boolean put(String aliasName, String dataStream, Boolean isWriteDataStream, String filter) {
            return getSingleProject().put(aliasName, dataStream, isWriteDataStream, filter);
        }

        public Builder removeDataStream(String name) {
            getSingleProject().removeDataStream(name);
            return this;
        }

        public boolean removeDataStreamAlias(String aliasName, String dataStreamName, boolean mustExist) {
            return getSingleProject().removeDataStreamAlias(aliasName, dataStreamName, mustExist);
        }

        public Builder putCustom(String type, ClusterCustom custom) {
            customs.put(type, Objects.requireNonNull(custom, type));
            return this;
        }

        public Builder putCustom(String type, ProjectCustom custom) {
            return putProjectCustom(type, custom);
        }

        public ClusterCustom getCustom(String type) {
            return customs.get(type);
        }

        public Builder removeCustom(String type) {
            customs.remove(type);
            return this;
        }

        public Builder removeCustomIf(BiPredicate<String, ? super ClusterCustom> p) {
            customs.removeAll(p);
            return this;
        }

        public Builder customs(Map<String, ClusterCustom> clusterCustoms) {
            clusterCustoms.forEach((key, value) -> Objects.requireNonNull(value, key));
            customs.putAllFromMap(clusterCustoms);
            return this;
        }

        public Builder putProjectCustom(String type, ProjectCustom custom) {
            getSingleProject().putCustom(type, Objects.requireNonNull(custom, type));
            return this;
        }

        public Builder removeProjectCustom(String type) {
            getSingleProject().removeCustom(type);
            return this;
        }

        public Builder removeProjectCustomIf(BiPredicate<String, ? super ProjectCustom> p) {
            getSingleProject().removeCustomIf(p);
            return this;
        }

        public Builder projectCustoms(Map<String, ProjectCustom> projectCustoms) {
            projectCustoms.forEach((key, value) -> Objects.requireNonNull(value, key));
            getSingleProject().customs(projectCustoms);
            return this;
        }

        /**
         * Adds a map of namespace to {@link ReservedStateMetadata} into the metadata builder
         * @param reservedStateMetadata a map of namespace to {@link ReservedStateMetadata}
         * @return {@link Builder}
         */
        public Builder put(Map<String, ReservedStateMetadata> reservedStateMetadata) {
            this.reservedStateMetadata.putAll(reservedStateMetadata);
            return this;
        }

        /**
         * Adds a {@link ReservedStateMetadata} for a given namespace to the metadata builder
         * @param metadata a {@link ReservedStateMetadata}
         * @return {@link Builder}
         */
        public Builder put(ReservedStateMetadata metadata) {
            reservedStateMetadata.put(metadata.namespace(), metadata);
            return this;
        }

        /**
         * Removes a {@link ReservedStateMetadata} for a given namespace
         * @param metadata a {@link ReservedStateMetadata}
         * @return {@link Builder}
         */
        public Builder removeReservedState(ReservedStateMetadata metadata) {
            reservedStateMetadata.remove(metadata.namespace());
            return this;
        }

        public Builder indexGraveyard(final IndexGraveyard indexGraveyard) {
            getSingleProject().indexGraveyard(indexGraveyard);
            return this;
        }

        public IndexGraveyard indexGraveyard() {
            return getSingleProject().indexGraveyard();
        }

        public Builder updateSettings(Settings settings, String... indices) {
            getSingleProject().updateSettings(settings, indices);
            return this;
        }

        /**
         * Update the number of replicas for the specified indices.
         *
         * @param numberOfReplicas the number of replicas
         * @param indices          the indices to update the number of replicas for
         * @return the builder
         */
        public Builder updateNumberOfReplicas(final int numberOfReplicas, final String[] indices) {
            getSingleProject().updateNumberOfReplicas(numberOfReplicas, indices);
            return this;
        }

        public Builder coordinationMetadata(CoordinationMetadata coordinationMetadata) {
            this.coordinationMetadata = coordinationMetadata;
            return this;
        }

        public Settings transientSettings() {
            return this.transientSettings;
        }

        public Builder transientSettings(Settings settings) {
            this.transientSettings = settings;
            return this;
        }

        public Settings persistentSettings() {
            return this.persistentSettings;
        }

        public Builder persistentSettings(Settings settings) {
            this.persistentSettings = settings;
            return this;
        }

        public Builder hashesOfConsistentSettings(DiffableStringMap hashesOfConsistentSettings) {
            this.hashesOfConsistentSettings = hashesOfConsistentSettings;
            return this;
        }

        public Builder hashesOfConsistentSettings(Map<String, String> hashesOfConsistentSettings) {
            this.hashesOfConsistentSettings = new DiffableStringMap(hashesOfConsistentSettings);
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder clusterUUID(String clusterUUID) {
            this.clusterUUID = clusterUUID;
            return this;
        }

        public Builder clusterUUIDCommitted(boolean clusterUUIDCommitted) {
            this.clusterUUIDCommitted = clusterUUIDCommitted;
            return this;
        }

        public Builder generateClusterUuidIfNeeded() {
            if (clusterUUID.equals(UNKNOWN_CLUSTER_UUID)) {
                clusterUUID(UUIDs.randomBase64UUID());
            }
            return this;
        }

        /**
         * @return a new <code>Metadata</code> instance
         */
        public Metadata build() {
            return build(false);
        }

        public Metadata build(boolean skipNameCollisionChecks) {
            if (projectMetadata.isEmpty()) {
                createDefaultProject();
            }
            return new Metadata(
                clusterUUID,
                clusterUUIDCommitted,
                version,
                coordinationMetadata,
                Collections.unmodifiableMap(Maps.transformValues(projectMetadata, m -> m.build(skipNameCollisionChecks))),
                transientSettings,
                persistentSettings,
                Settings.builder().put(persistentSettings).put(transientSettings).build(),
                hashesOfConsistentSettings,
                customs.build(),
                Collections.unmodifiableMap(reservedStateMetadata)
            );
        }

        private ProjectMetadata.Builder createDefaultProject() {
            return projectMetadata.put(DEFAULT_PROJECT_ID, new ProjectMetadata.Builder(Map.of(), 0));
        }

        public static Metadata fromXContent(XContentParser parser) throws IOException {
            Builder builder = new Builder();

            // we might get here after the meta-data element, or on a fresh parser
            XContentParser.Token token = parser.currentToken();
            String currentFieldName = parser.currentName();
            if ("meta-data".equals(currentFieldName) == false) {
                token = parser.nextToken();
                if (token == XContentParser.Token.START_OBJECT) {
                    // move to the field name (meta-data)
                    XContentParserUtils.ensureExpectedToken(XContentParser.Token.FIELD_NAME, parser.nextToken(), parser);
                    // move to the next object
                    token = parser.nextToken();
                }
                currentFieldName = parser.currentName();
            }

            if ("meta-data".equals(currentFieldName) == false) {
                throw new IllegalArgumentException("Expected [meta-data] as a field name but got " + currentFieldName);
            }
            XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, token, parser);

            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if ("cluster_coordination".equals(currentFieldName)) {
                        builder.coordinationMetadata(CoordinationMetadata.fromXContent(parser));
                    } else if ("settings".equals(currentFieldName)) {
                        builder.persistentSettings(Settings.fromXContent(parser));
                    } else if ("indices".equals(currentFieldName)) {
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            builder.put(IndexMetadata.Builder.fromXContent(parser), false);
                        }
                    } else if ("hashes_of_consistent_settings".equals(currentFieldName)) {
                        builder.hashesOfConsistentSettings(parser.mapStrings());
                    } else if ("templates".equals(currentFieldName)) {
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            builder.put(IndexTemplateMetadata.Builder.fromXContent(parser, parser.currentName()));
                        }
                    } else if ("reserved_state".equals(currentFieldName)) {
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            builder.put(ReservedStateMetadata.fromXContent(parser));
                        }
                    } else if ("project".equals(currentFieldName)) {
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (token == XContentParser.Token.FIELD_NAME) {
                                currentFieldName = parser.currentName();
                                if (parser.nextToken() == XContentParser.Token.START_OBJECT) {
                                    parseCustomObject(parser, currentFieldName, ProjectCustom.class, builder::putProjectCustom);
                                }
                            }
                        }
                    } else {
                        // Older clusters didn't separate cluster-scoped and project-scope customs so a top-level custom object might
                        // actually be a project-scoped custom
                        final NamedXContentRegistry registry = parser.getXContentRegistry();
                        if (registry.hasParser(ClusterCustom.class, currentFieldName, parser.getRestApiVersion())) {
                            parseCustomObject(parser, currentFieldName, ClusterCustom.class, builder::putCustom);
                        } else if (registry.hasParser(ProjectCustom.class, currentFieldName, parser.getRestApiVersion())) {
                            parseCustomObject(parser, currentFieldName, ProjectCustom.class, builder::putProjectCustom);
                        } else {
                            logger.warn("Skipping unknown custom object with type {}", currentFieldName);
                            parser.skipChildren();
                        }
                    }
                } else if (token.isValue()) {
                    if ("version".equals(currentFieldName)) {
                        builder.version(parser.longValue());
                    } else if ("cluster_uuid".equals(currentFieldName) || "uuid".equals(currentFieldName)) {
                        builder.clusterUUID(parser.text());
                    } else if ("cluster_uuid_committed".equals(currentFieldName)) {
                        builder.clusterUUIDCommitted(parser.booleanValue());
                    } else {
                        throw new IllegalArgumentException("Unexpected field [" + currentFieldName + "]");
                    }
                } else {
                    throw new IllegalArgumentException("Unexpected token " + token);
                }
            }
            XContentParserUtils.ensureExpectedToken(XContentParser.Token.END_OBJECT, parser.nextToken(), parser);
            return builder.build();
        }

        private static <C extends MetadataCustom<C>> void parseCustomObject(
            XContentParser parser,
            String name,
            Class<C> categoryClass,
            BiConsumer<String, C> consumer
        ) throws IOException {
            try {
                C custom = parser.namedObject(categoryClass, name, null);
                consumer.accept(custom.getWriteableName(), custom);
            } catch (NamedObjectNotFoundException _ex) {
                logger.warn("Skipping unknown custom [{}] object with type {}", categoryClass.getSimpleName(), name);
                parser.skipChildren();
            }
        }
    }

    private static final ToXContent.Params FORMAT_PARAMS;
    static {
        Map<String, String> params = Maps.newMapWithExpectedSize(2);
        params.put("binary", "true");
        params.put(Metadata.CONTEXT_MODE_PARAM, Metadata.CONTEXT_MODE_GATEWAY);
        FORMAT_PARAMS = new ToXContent.MapParams(params);
    }

    /**
     * State format for {@link Metadata} to write to and load from disk
     */
    public static final MetadataStateFormat<Metadata> FORMAT = new MetadataStateFormat<>(GLOBAL_STATE_FILE_PREFIX) {

        @Override
        public void toXContent(XContentBuilder builder, Metadata state) throws IOException {
            ChunkedToXContent.wrapAsToXContent(state).toXContent(builder, FORMAT_PARAMS);
        }

        @Override
        public Metadata fromXContent(XContentParser parser) throws IOException {
            return Builder.fromXContent(parser);
        }
    };
}
