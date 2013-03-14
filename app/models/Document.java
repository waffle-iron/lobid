/* Copyright 2012-2013 Fabian Steeg. Licensed under the Eclipse Public License 1.0 */

package models;

import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchQuery;
import static org.elasticsearch.index.query.QueryBuilders.multiMatchQuery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder.Operator;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.json.simple.JSONValue;
import org.lobid.lodmill.JsonLdConverter;
import org.lobid.lodmill.JsonLdConverter.Format;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.hp.hpl.jena.shared.BadURIException;

/**
 * Documents returned from the ElasticSearch index.
 * 
 * @author Fabian Steeg (fsteeg)
 */
public class Document {

	public static final InetSocketTransportAddress ES_SERVER =
			new InetSocketTransportAddress("10.1.2.111", 9300); // NOPMD
	public static final String ES_CLUSTER_NAME = "es-lod-hydra";
	public static ImmutableMap<String, Map<String, List<String>>> searchFieldsMap =
			new ImmutableMap.Builder<String, Map<String, List<String>>>()
					.put(
							"lobid-index",
							new ImmutableMap.Builder<String, List<String>>()
									.put(
											"author",
											Arrays
													.asList(
															"http://purl.org/dc/elements/1.1/creator#preferredNameForThePerson",
															"http://purl.org/dc/elements/1.1/creator#dateOfBirth",
															"http://purl.org/dc/elements/1.1/creator#dateOfDeath"))
									.put(
											"id",
											Arrays.asList("@id",
													"http://purl.org/ontology/bibo/isbn13",
													"http://purl.org/ontology/bibo/isbn10")).build())
					.put(
							"gnd-index",
							new ImmutableMap.Builder<String, List<String>>()
									.put(
											"author",
											Arrays
													.asList(
															"http://d-nb.info/standards/elementset/gnd#preferredNameForThePerson",
															"http://d-nb.info/standards/elementset/gnd#dateOfBirth",
															"http://d-nb.info/standards/elementset/gnd#dateOfDeath"))
									.build())
					.put(
							"lobid-orgs-index",
							new ImmutableMap.Builder<String, List<String>>().put(
									"title",
									Arrays
											.asList("http://www.w3.org/2004/02/skos/core#prefLabel"))
									.build()).build();

	public static List<String> searchFields = searchFieldsMap.get("lobid-index")
			.get("author");

	private static final Client CLIENT = new TransportClient(ImmutableSettings
			.settingsBuilder().put("cluster.name", ES_CLUSTER_NAME).build())
			.addTransportAddress(ES_SERVER);

	public transient String matchedField;
	public transient String source;
	public transient String id; // NOPMD

	private static final Logger LOG = LoggerFactory.getLogger(Document.class);

	public Document(final String id, final String source) { // NOPMD
		this.id = id;
		this.source = source;
	}

	public Document() {
		/* Empty constructor required by Play */
	}

	public String as(final Format format) { // NOPMD
		final JsonLdConverter converter = new JsonLdConverter(format);
		final String json = JSONValue.toJSONString(JSONValue.parse(source));
		String result = "";
		try {
			result = converter.toRdf(json);
		} catch (BadURIException x) {
			LOG.error(x.getMessage(), x);
		}
		return result;
	}

	public static List<Document> search(final String term, final String index,
			final String category) {
		validate(index, category);
		final String query = term.toLowerCase();
		final SearchRequestBuilder requestBuilder =
				CLIENT.prepareSearch(index)
						.setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
						.setQuery(constructQuery(query, category));
		/* TODO: pass limit as a parameter */
		final SearchResponse response =
				requestBuilder.setFrom(0).setSize(50).setExplain(true).execute()
						.actionGet();
		final SearchHits hits = response.getHits();
		return asDocuments(query, hits);
	}

	private static void validate(final String index, final String category) {
		if (searchFieldsMap.get(index) == null) {
			throw new IllegalArgumentException(String.format(
					"Invalid index ('%s') - valid indexes: %s", index,
					searchFieldsMap.keySet()));
		}
		searchFields = searchFieldsMap.get(index).get(category);
		if (searchFields == null) {
			throw new IllegalArgumentException(String.format(
					"Invalid type ('%s') for specified index ('%s') - valid types: %s",
					category, index, searchFieldsMap.get(index).keySet()));
		}
	}

	private static QueryBuilder constructQuery(final String search,
			final String category) {
		final String lifeDates = "\\((\\d+)-(\\d*)\\)";
		final Matcher matcher =
				Pattern.compile("[^(]+" + lifeDates).matcher(search);
		QueryBuilder query = null;
		if (matcher.find() && category.equals("author")) {
			query = createAuthorQuery(lifeDates, search, matcher);
		} else if (category.equals("id")) {
			final String fixedQuery = search.matches("ht[\\d]{9}") ?
			/* HT number -> URL (temp. until we have an HBZ-ID field) */
			"http://lobid.org/resource/" + search : search;
			query =
					multiMatchQuery(fixedQuery, searchFields.toArray(new String[] {}));
		} else {
			/* Search all in name field: */
			query =
					boolQuery().must(
							matchQuery(searchFields.get(0), search).operator(Operator.AND));
		}
		LOG.debug("Using query: " + query);
		return query;
	}

	private static QueryBuilder createAuthorQuery(final String lifeDates,
			final String search, final Matcher matcher) {
		/* Search name in name field and birth in birth field: */
		final BoolQueryBuilder birthQuery =
				boolQuery()
						.must(
								matchQuery(searchFields.get(0),
										search.replaceAll(lifeDates, "").trim()).operator(
										Operator.AND)).must(
								matchQuery(searchFields.get(1), matcher.group(1)));
		return matcher.group(2).equals("") ? birthQuery :
		/* If we have one, search death in death field: */
		birthQuery.must(matchQuery(searchFields.get(2), matcher.group(2)));
	}

	private static List<Document> asDocuments(final String query,
			final SearchHits hits) {
		final List<Document> res = new ArrayList<>();
		for (SearchHit hit : hits) {
			final Document document =
					new Document(hit.getId(), new String(hit.source()));
			withMatchedField(query, hit, document);
			res.add(document);
		}
		final Predicate<Document> predicate = new Predicate<Document>() {
			public boolean apply(final Document doc) {
				return doc.matchedField != null;
			}
		};
		return ImmutableList.copyOf(Iterables.filter(res, predicate));
	}

	private static void withMatchedField(final String query, final SearchHit hit,
			final Document document) {
		final Object matchedField = firstExisting(hit);
		if (matchedField instanceof List
				&& ((List<?>) matchedField).get(0) instanceof String) {
			@SuppressWarnings("unchecked")
			final List<String> list = (List<String>) matchedField;
			document.matchedField = firstMatching(query, list);
		} else if (searchFields.get(0).contains("preferredNameForThePerson")) {
			final Object birth = hit.getSource().get(searchFields.get(1));
			final Object death = hit.getSource().get(searchFields.get(2));
			if (birth == null) {
				document.matchedField = matchedField.toString();
			} else {
				final String format =
						String.format("%s (%s-%s)", matchedField.toString(),
								birth.toString(), death == null ? "" : death.toString());
				document.matchedField = format;
			}
		} else if (matchedField instanceof String) {
			document.matchedField = matchedField.toString();
		}
	}

	private static Object firstExisting(final SearchHit hit) {
		for (String field : searchFields) {
			if (hit.getSource().containsKey(field)) {
				return hit.getSource().get(field);
			}
		}
		return null;
	}

	private static String firstMatching(final String query,
			final List<String> list) {
		final Predicate<String> predicate = new Predicate<String>() {
			public boolean apply(final String string) {
				return string.toLowerCase().contains(query);
			}
		};
		return Iterables.tryFind(list, predicate).orNull();
	}
}
