/*
 * Copyright 2023-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.vectorstore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.alibaba.fastjson.JSONObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.R.Status;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.ReleaseCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.param.index.DropIndexParam;
import io.milvus.response.QueryResultsWrapper.RowRecord;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

/**
 * @author Christian Tzolov
 */
public class MilvusVectorStore implements VectorStore, SmartLifecycle {

	private static final Logger logger = LoggerFactory.getLogger(MilvusVectorStore.class);

	public static final int OPENAI_EMBEDDING_DIMENSION_SIZE = 1536;

	public static final String DEFAULT_DATABASE_NAME = "default";

	public static final String DEFAULT_COLLECTION_NAME = "vector_store";

	public static final String DOC_ID_FIELD_NAME = "doc_id";

	public static final String CONTENT_FIELD_NAME = "content";

	public static final String METADATA_FIELD_NAME = "metadata";

	public static final String EMBEDDING_FIELD_NAME = "embedding";

	// Metadata, automatically assigned by Milvus.
	public static final String DISTANCE_FIELD_NAME = "distance";

	public static final List<String> SEARCH_OUTPUT_FIELDS = Arrays.asList(DOC_ID_FIELD_NAME, CONTENT_FIELD_NAME,
			METADATA_FIELD_NAME);

	private final MilvusServiceClient milvusClient;

	private final EmbeddingClient embeddingClient;

	private final MilvusVectorStoreConfig config;

	/**
	 * Configuration for the Milvus vector store.
	 */
	public static class MilvusVectorStoreConfig {

		private final String databaseName;

		private final String collectionName;

		private final int embeddingDimension;

		private final IndexType indexType;

		private final MetricType metricType;

		private final String indexParameters;

		/**
		 * Start building a new configuration.
		 * @return The entry point for creating a new configuration.
		 */
		public static Builder builder() {

			return new Builder();
		}

		/**
		 * {@return the default config}
		 */
		public static MilvusVectorStoreConfig defaultConfig() {

			return builder().build();
		}

		private MilvusVectorStoreConfig(Builder builder) {
			this.databaseName = builder.databaseName;
			this.collectionName = builder.collectionName;
			this.embeddingDimension = builder.embeddingDimension;
			this.indexType = builder.indexType;
			this.metricType = builder.metricType;
			this.indexParameters = builder.indexParameters;
		}

		public static class Builder {

			private String databaseName = DEFAULT_DATABASE_NAME;

			private String collectionName = DEFAULT_COLLECTION_NAME;

			private int embeddingDimension = OPENAI_EMBEDDING_DIMENSION_SIZE;

			private IndexType indexType = IndexType.IVF_FLAT;

			private MetricType metricType = MetricType.L2;

			private String indexParameters = "{\"nlist\":1024}";

			private Builder() {
			}

			/**
			 * Configures the Milvus metric type to use. Leave {@literal null} or blank to
			 * use the metric metric.
			 * @param metricType the metric type to use
			 * @return this builder
			 */
			public Builder withMetricType(MetricType metricType) {
				Assert.notNull(metricType, "Collection Name must not be empty");
				Assert.isTrue(metricType == MetricType.IP || metricType == MetricType.L2,
						"Only the text metric types IP and L2 are supported");

				this.metricType = metricType;
				return this;
			}

			/**
			 * Configures the Milvus index type to use. Leave {@literal null} or blank to
			 * use the default index.
			 * @param indexType the index type to use
			 * @return this builder
			 */
			public Builder withIndexType(IndexType indexType) {
				this.indexType = indexType;
				return this;
			}

			/**
			 * Configures the Milvus index parameters to use. Leave {@literal null} or
			 * blank to use the default index parameters.
			 * @param indexParameters the index parameters to use
			 * @return this builder
			 */
			public Builder withIndexParameters(String indexParameters) {
				this.indexParameters = indexParameters;
				return this;
			}

			/**
			 * Configures the Milvus database name to use. Leave {@literal null} or blank
			 * to use the default database.
			 * @param databaseName the database name to use
			 * @return this builder
			 */
			public Builder withDatabaseName(String databaseName) {
				this.databaseName = databaseName;
				return this;
			}

			/**
			 * Configures the Milvus collection name to use. Leave {@literal null} or
			 * blank to use the default collection name.
			 * @param collectionName the collection name to use
			 * @return this builder
			 */
			public Builder withCollectionName(String collectionName) {
				this.collectionName = collectionName;
				return this;
			}

			/**
			 * Configures the size of the embedding. Defaults to {@literal 1536}, inline
			 * with OpenAIs embeddings.
			 * @param newEmbeddingDimension The dimension of the embedding
			 * @return this builder
			 */
			public Builder withEmbeddingDimension(int newEmbeddingDimension) {

				Assert.isTrue(newEmbeddingDimension >= 1 && newEmbeddingDimension <= 2048,
						"Dimension has to be withing the boundaries 1 and 2048 (inclusively)");

				this.embeddingDimension = newEmbeddingDimension;
				return this;
			}

			/**
			 * {@return the immutable configuration}
			 */
			public MilvusVectorStoreConfig build() {
				return new MilvusVectorStoreConfig(this);
			}

		}

	}

	public MilvusVectorStore(MilvusServiceClient milvusClient, EmbeddingClient embeddingClient) {
		this(milvusClient, embeddingClient, MilvusVectorStoreConfig.defaultConfig());
	}

	public MilvusVectorStore(MilvusServiceClient milvusClient, EmbeddingClient embeddingClient,
			MilvusVectorStoreConfig config) {

		Assert.notNull(milvusClient, "MilvusServiceClient must not be null");
		Assert.notNull(milvusClient, "EmbeddingClient must not be null");

		this.milvusClient = milvusClient;
		this.embeddingClient = embeddingClient;
		this.config = config;
	}

	@Override
	public void add(List<Document> documents) {

		Assert.notNull(documents, "Documents must not be null");

		List<String> docIdArray = new ArrayList<>();
		List<String> contentArray = new ArrayList<>();
		List<JSONObject> metadataArray = new ArrayList<>();
		List<List<Float>> embeddingArray = new ArrayList<>();

		for (Document document : documents) {
			List<Double> embedding = this.embeddingClient.embed(document);

			docIdArray.add(document.getId());
			// Use a (future) DocumentTextLayoutFormatter instance to extract
			// the content used to compute the embeddings
			contentArray.add(document.getText());
			metadataArray.add(new JSONObject(document.getMetadata()));
			embeddingArray.add(toFloatList(embedding));
		}

		List<InsertParam.Field> fields = new ArrayList<>();
		fields.add(new InsertParam.Field(DOC_ID_FIELD_NAME, docIdArray));
		fields.add(new InsertParam.Field(CONTENT_FIELD_NAME, contentArray));
		fields.add(new InsertParam.Field(METADATA_FIELD_NAME, metadataArray));
		fields.add(new InsertParam.Field(EMBEDDING_FIELD_NAME, embeddingArray));

		InsertParam insertParam = InsertParam.newBuilder()
			.withDatabaseName(this.config.databaseName)
			.withCollectionName(this.config.collectionName)
			.withFields(fields)
			.build();

		R<MutationResult> status = this.milvusClient.insert(insertParam);
		if (status.getException() != null) {
			throw new RuntimeException("Failed to insert:", status.getException());
		}
		this.milvusClient.flush(FlushParam.newBuilder()
			.withDatabaseName(this.config.databaseName)
			.addCollectionName(this.config.collectionName)
			.build());
	}

	@Override
	public Optional<Boolean> delete(List<String> idList) {
		Assert.notNull(idList, "Document id list must not be null");

		String deleteExpression = String.format("%s in [%s]", DOC_ID_FIELD_NAME,
				idList.stream().map(id -> "'" + id + "'").collect(Collectors.joining(",")));

		R<MutationResult> status = this.milvusClient.delete(DeleteParam.newBuilder()
			.withCollectionName(this.config.collectionName)
			.withExpr(deleteExpression)
			.build());

		long deleteCount = status.getData().getDeleteCnt();
		if (deleteCount != idList.size()) {
			logger.warn(String.format("Deleted only %s entries from requested %s ", deleteCount, idList.size()));
		}

		return Optional.of(status.getStatus() == Status.Success.getCode());
	}

	@Override
	public List<Document> similaritySearch(String query) {
		return this.similaritySearch(query, 4);
	}

	@Override
	public List<Document> similaritySearch(String query, int topK) {
		return similaritySearch(query, topK, 0.0D);
	}

	@Override
	public List<Document> similaritySearch(String query, int topK, double similarityThreshold) {
		Assert.notNull(query, "Query string must not be null");

		List<Double> embedding = this.embeddingClient.embed(query);

		SearchParam searchParam = SearchParam.newBuilder()
			.withCollectionName(this.config.collectionName)
			.withConsistencyLevel(ConsistencyLevelEnum.STRONG)
			.withMetricType(this.config.metricType)
			.withOutFields(SEARCH_OUTPUT_FIELDS)
			.withTopK(topK)
			.withVectors(List.of(toFloatList(embedding)))
			.withVectorFieldName(EMBEDDING_FIELD_NAME)
			.build();

		R<SearchResults> respSearch = milvusClient.search(searchParam);

		if (respSearch.getException() != null) {
			throw new RuntimeException("Search failed!", respSearch.getException());
		}

		SearchResultsWrapper wrapperSearch = new SearchResultsWrapper(respSearch.getData().getResults());

		return wrapperSearch.getRowRecords()
			.stream()
			.filter(rowRecord -> getResultSimilarity(rowRecord) >= similarityThreshold)
			.map(rowRecord -> {
				String docId = (String) rowRecord.get(DOC_ID_FIELD_NAME);
				String content = (String) rowRecord.get(CONTENT_FIELD_NAME);
				JSONObject metadata = (JSONObject) rowRecord.get(METADATA_FIELD_NAME);
				// inject the distance into the metadata.
				metadata.put(DISTANCE_FIELD_NAME, 1 - getResultSimilarity(rowRecord));
				return new Document(docId, content, metadata.getInnerMap());
			})
			.toList();
	}

	private float getResultSimilarity(RowRecord rowRecord) {
		Float distance = (Float) rowRecord.get(DISTANCE_FIELD_NAME);
		return (this.config.metricType == MetricType.IP) ? distance : (1 - distance);
	}

	private List<Float> toFloatList(List<Double> embeddingDouble) {
		return embeddingDouble.stream().map(Number::floatValue).toList();
	}

	// ---------------------------------------------------------------------------------
	// SmartLifecycle
	// ---------------------------------------------------------------------------------
	private AtomicBoolean isRunning = new AtomicBoolean(false);

	@Override
	public void start() {
		try {
			createCollection();
		}
		finally {
			this.isRunning.set(true);
		}
	}

	@Override
	public void stop() {
		try {
			if (isDatabaseCollectionExists()) {
				this.milvusClient.releaseCollection(
						ReleaseCollectionParam.newBuilder().withCollectionName(this.config.collectionName).build());
			}
		}
		finally {
			this.isRunning.set(false);
		}
	}

	@Override
	public boolean isRunning() {
		return this.isRunning.get();
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	private boolean isDatabaseCollectionExists() {
		return this.milvusClient
			.hasCollection(HasCollectionParam.newBuilder()
				.withDatabaseName(this.config.databaseName)
				.withCollectionName(this.config.collectionName)
				.build())
			.getData();
	}

	// used by the test as well
	void createCollection() {

		if (!isDatabaseCollectionExists()) {

			FieldType docIdFieldType = FieldType.newBuilder()
				.withName(DOC_ID_FIELD_NAME)
				.withDataType(DataType.VarChar)
				.withMaxLength(36)
				.withPrimaryKey(true)
				.withAutoID(false)
				.build();
			FieldType contentFieldType = FieldType.newBuilder()
				.withName(CONTENT_FIELD_NAME)
				.withDataType(DataType.VarChar)
				.withMaxLength(65535)
				.build();
			FieldType metadataFieldType = FieldType.newBuilder()
				.withName(METADATA_FIELD_NAME)
				.withDataType(DataType.JSON)
				.build();
			FieldType embeddingFieldType = FieldType.newBuilder()
				.withName(EMBEDDING_FIELD_NAME)
				.withDataType(DataType.FloatVector)
				.withDimension(this.config.embeddingDimension)
				.build();

			CreateCollectionParam createCollectionReq = CreateCollectionParam.newBuilder()
				.withDatabaseName(this.config.databaseName)
				.withCollectionName(this.config.collectionName)
				.withDescription("Spring AI Vector Store")
				.withConsistencyLevel(ConsistencyLevelEnum.STRONG)
				.withShardsNum(2)
				.addFieldType(docIdFieldType)
				.addFieldType(contentFieldType)
				.addFieldType(metadataFieldType)
				.addFieldType(embeddingFieldType)
				.build();

			R<RpcStatus> collectionStatus = this.milvusClient.createCollection(createCollectionReq);
			if (collectionStatus.getException() != null) {
				throw new RuntimeException("Failed to create collection", collectionStatus.getException());
			}
		}

		R<DescribeIndexResponse> indexDescriptionResponse = this.milvusClient
			.describeIndex(DescribeIndexParam.newBuilder()
				.withDatabaseName(this.config.databaseName)
				.withCollectionName(this.config.collectionName)
				.build());

		if (indexDescriptionResponse.getData() == null) {
			R<RpcStatus> indexStatus = this.milvusClient.createIndex(CreateIndexParam.newBuilder()
				.withDatabaseName(this.config.databaseName)
				.withCollectionName(this.config.collectionName)
				.withFieldName(EMBEDDING_FIELD_NAME)
				.withIndexType(this.config.indexType)
				.withMetricType(this.config.metricType)
				.withExtraParam(this.config.indexParameters)
				.withSyncMode(Boolean.FALSE)
				.build());

			if (indexStatus.getException() != null) {
				throw new RuntimeException("Failed to create Index", indexStatus.getException());
			}
		}

		R<RpcStatus> loadCollectionStatus = this.milvusClient.loadCollection(LoadCollectionParam.newBuilder()
			.withDatabaseName(this.config.databaseName)
			.withCollectionName(this.config.collectionName)
			.build());

		if (loadCollectionStatus.getException() != null) {
			throw new RuntimeException("Collection loading failed!", loadCollectionStatus.getException());
		}
	}

	// used by the test as well
	void dropCollection() {

		R<RpcStatus> status = this.milvusClient.releaseCollection(
				ReleaseCollectionParam.newBuilder().withCollectionName(this.config.collectionName).build());

		if (status.getException() != null) {
			throw new RuntimeException("Release collection failed!", status.getException());
		}

		status = this.milvusClient
			.dropIndex(DropIndexParam.newBuilder().withCollectionName(this.config.collectionName).build());

		if (status.getException() != null) {
			throw new RuntimeException("Drop Index failed!", status.getException());
		}

		status = this.milvusClient.dropCollection(DropCollectionParam.newBuilder()
			.withDatabaseName(this.config.databaseName)
			.withCollectionName(this.config.collectionName)
			.build());

		if (status.getException() != null) {
			throw new RuntimeException("Drop Collection failed!", status.getException());
		}
	}

}