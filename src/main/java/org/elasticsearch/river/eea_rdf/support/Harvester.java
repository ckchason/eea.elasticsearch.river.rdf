package org.elasticsearch.river.eea_rdf.support;

import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.client.Client;
import static org.elasticsearch.common.xcontent.XContentFactory.*;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.river.eea_rdf.settings.EEASettings;

import org.apache.jena.riot.RDFDataMgr ;
import org.apache.jena.riot.RDFLanguages ;

import com.hp.hpl.jena.rdf.model.*;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryParseException;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.graph.GraphMaker;
import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.graph.NodeFactory;
import com.hp.hpl.jena.datatypes.RDFDatatype;

import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Date;
import java.lang.StringBuffer;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Byte;
import java.lang.ClassCastException;
import java.text.SimpleDateFormat;
import java.io.IOException;

public class Harvester implements Runnable {

	private final ESLogger logger = Loggers.getLogger(Harvester.class);

	private Boolean indexAll = true;
	private String startTime;
	private List<String> rdfUrls;
	private String rdfEndpoint;
	private String rdfQuery;
	private int rdfQueryType;
	private List<String> rdfPropList;
	private Boolean rdfListType = false;
	private Boolean hasList = false;
	private Map<String, String> normalizeProp;
	private Map<String, String> normalizeObj;
	private Boolean willNormalizeProp = false;
	private Boolean willNormalizeObj = false;
	private Boolean addLanguage = false;
	private String language;
	private List<String> uriDescriptionList;
	private Boolean toDescribeURIs = false;
	private Boolean addUriForResource;
	private Boolean hasBlackMap = false;
	private Boolean hasWhiteMap = false;
	private Map<String,List<String>> blackMap;
	private Map<String,List<String>> whiteMap;

	private Client client;
	private String indexName;
	private String typeName;
	private int maxBulkActions;
	private int maxConcurrentRequests;

	private Boolean closed = false;

	public Harvester rdfUrl(String url) {
		url = url.substring(1, url.length() - 1);
		rdfUrls = Arrays.asList(url.split(","));
		return this;
	}

	public Harvester rdfEndpoint(String endpoint) {
		this.rdfEndpoint = endpoint;
		return this;
	}

	public Harvester rdfQuery(String query) {
		this.rdfQuery = query;
		return this;
	}

	public Harvester rdfQueryType(String queryType) {
		if(queryType.equals("select"))
			this.rdfQueryType = 1;
		else
			this.rdfQueryType = 0;
		return this;
	}

	public Harvester rdfPropList(List<String> list) {
		if(!list.isEmpty()) {
			hasList = true;
			rdfPropList = new ArrayList<String>(list);
		}
		return this;
	}

	public Harvester rdfListType(String listType) {
		if(listType.equals("white"))
			this.rdfListType = true;
		return this;
	}

	public Harvester rdfAddLanguage(Boolean rdfAddLanguage) {
		this.addLanguage = rdfAddLanguage;
		return this;
	}

	public Harvester rdfLanguage(String rdfLanguage) {
		this.language = rdfLanguage;
		if(!this.language.isEmpty()){
			this.addLanguage = true;
			if(!this.language.startsWith("\""))
				this.language = "\"" +  this.language + "\"";
		}
		return this;
	}

	public Harvester rdfNormalizationProp(Map<String, String> normalizeProp) {
		if(normalizeProp != null || !normalizeProp.isEmpty()) {
			willNormalizeProp = true;
			this.normalizeProp = normalizeProp;
		}
		return this;
	}

	public Harvester rdfNormalizationObj(Map<String, String> normalizeObj) {
		if(normalizeObj != null || !normalizeObj.isEmpty()) {
			willNormalizeObj = true;
			this.normalizeObj = normalizeObj;
		}
		return this;
	}

	public Harvester rdfBlackMap(Map<String,Object> blackMap) {
		if(blackMap != null || !blackMap.isEmpty()) {
			hasBlackMap = true;
			this.blackMap =  new HashMap<String,List<String>>();
			for(Map.Entry<String,Object> entry : blackMap.entrySet()) {
				this.blackMap.put(entry.getKey(), (List<String>)entry.getValue());
			}
		}
		return this;
	}

	public Harvester rdfWhiteMap(Map<String,Object> whiteMap) {
		if(whiteMap != null || !whiteMap.isEmpty()) {
			hasWhiteMap = true;
			this.whiteMap =  new HashMap<String,List<String>>();
			for(Map.Entry<String,Object> entry : whiteMap.entrySet()) {
				this.whiteMap.put(entry.getKey(), (List<String>)entry.getValue());
			}
		}
		return this;
	}

	public Harvester rdfURIDescription(String uriList) {
		uriList = uriList.substring(1, uriList.length() - 1);
		if(!uriList.isEmpty())
			toDescribeURIs = true;
		uriDescriptionList = Arrays.asList(uriList.split(","));
		return this;
	}

	public Harvester rdfAddUriForResource(Boolean rdfAddUriForResource) {
		this.addUriForResource = rdfAddUriForResource;
		return this;
	}

	public Harvester client(Client client) {
		this.client = client;
		return this;
	}

	public Harvester index(String indexName) {
		this.indexName = indexName;
		return this;
	}

	public Harvester type(String typeName) {
		this.typeName = typeName;
		return this;
	}

	public Harvester rdfIndexType(String indexType) {
		if (indexType.equals("sync"))
			this.indexAll = false;
		return this;
	}

	public Harvester rdfStartTime(String startTime) {
		this.startTime = startTime;
		return this;
	}

	public void setClose(Boolean value) {
		this.closed = value;
	}

	@Override
	public void run() {
		long currentTime = System.currentTimeMillis();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		Date now = new Date(currentTime);

		if(indexAll)
			runIndexAll();
		else
			runSync();
		BulkRequestBuilder bulkRequest = client.prepareBulk();
		try {
			bulkRequest.add(client.prepareIndex(indexName, "stats", "1")
					.setSource(jsonBuilder()
						.startObject()
						.field("last_update", sdf.format(now))
					.endObject()));
		} catch (IOException ioe) {}
		BulkResponse bulkResponse = bulkRequest.execute().actionGet();
		return ;
	}

	public void runSync() {
		logger.info(
				"Starting RDF synchronizer: from [{}], endpoint [{}], " +
				"index name [{}], type name [{}]",
				startTime, rdfEndpoint,	indexName, typeName);

		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		Date lastUpdate = new Date(System.currentTimeMillis());

		if(startTime.isEmpty()) {
			GetResponse response = client.prepareGet(indexName, "stats", "1")
				.setFields("last_update")
				.execute()
				.actionGet();
			startTime = (String)response.getField("last_update").getValue();
		}

		try {
			lastUpdate = sdf.parse(startTime);
		} catch (Exception e){}

		sync();

		if(this.closed){
			logger.info("Ended synchronization from [{}], for endpoint [{}]," +
					"at index name {}, type name {}",
					lastUpdate, rdfEndpoint, indexName, typeName);
			return;
		}
	}

	public void sync() {
		rdfQuery = "PREFIX xsd:<http://www.w3.org/2001/XMLSchema#> " +
			"SELECT ?resource WHERE { " +
			"?resource <http://cr.eionet.europa.eu/ontologies/contreg.rdf#lastRefreshed> ?time ." +
			" FILTER (?time > xsd:dateTime(\"" + startTime + "\")) . " +
			"FILTER (strStarts(str(?resource),'http://www.eea.europa.eu'))}";

		try {
			Query query = QueryFactory.create(rdfQuery);
			QueryExecution qexec = QueryExecutionFactory.sparqlService(
					rdfEndpoint, query);
			try {
				ResultSet results = qexec.execSelect();
				Model sparqlModel = ModelFactory.createDefaultModel();
				Graph graph = sparqlModel.getGraph();
				rdfUrls = new ArrayList<String>();

				while(results.hasNext()) {
					QuerySolution sol = results.nextSolution();
					Iterator<String> iterS = sol.varNames();
					try {
						String value = sol.getResource("resource").toString();
						rdfUrls.add(value.substring(0, value.length() - 6));
					} catch (NoSuchElementException nsee) {}
				}
			} catch (Exception e) {
				logger.info(
						"Encountered a [{}] while querying the endpoint for sync", e);
			} finally {
				qexec.close();
			}
		} catch (QueryParseException qpe) {
			logger.info(
					"Could not parse [{}]. Please provide a relevant quey	{}",
					rdfQuery, qpe);
		}
		for (String uri : rdfUrls) {
			//make query for each/all entries
			switch(rdfQueryType) {
				case 0: rdfQuery = "CONSTRUCT {<" + uri + "> ?p ?o WHERE { <" + uri +
								"> ?p ?o}";
								break;
				case 1: rdfQuery = "SELECT <" + uri + "> as ?s ?p ?o WHERE { <" + uri +
								"> ?p ?o}";
								break;
				case 2: rdfQuery = "DESCRIBE <" + uri + ">";
								break;
				default: rdfQuery = "";
								 break;
			}
			rdfQuery = "DESCRIBE <" + uri + ">";
			try {
				Query query = QueryFactory.create(rdfQuery);
				QueryExecution qexec = QueryExecutionFactory.sparqlService(
						rdfEndpoint, query);
				try {
					Model constructModel = ModelFactory.createDefaultModel();

					qexec.execConstruct(constructModel);
					BulkRequestBuilder bulkRequest = client.prepareBulk();
					addModelToES(constructModel, bulkRequest);
				} catch (Exception e) {
					logger.info(
							"Encountered a [{}] while querying the endpoint	for sync", e);
				} finally {	qexec.close(); }
			} catch (QueryParseException  qpe) {
				logger.info(
						"Could not parse [{}]. Please provide a relevant quey	{}",
						rdfQuery, qpe);
			}

		}
	}

	public void runIndexAll() {

		logger.info(
				"Starting RDF harvester: endpoint [{}], query [{}]," +
				"URLs [{}], index name [{}], typeName {}",
				rdfEndpoint, rdfQuery, rdfUrls, indexName, typeName);

		while (true) {
			if(this.closed){
				logger.info("Ended harvest for endpoint [{}], query [{}]," +
						"URLs [{}], index name {}, type name {}",
						rdfEndpoint, rdfQuery, rdfUrls, indexName, typeName);

				return;
			}

			/**
			 * Harvest from a SPARQL endpoint
			 */
			if(!rdfQuery.isEmpty()) {
				harvestFromEndpoint();
			}

			/**
			 * Harvest from RDF dumps
			 */
			harvestFromDumps();

			closed = true;
		}
	}


	private void harvestWithSelect(QueryExecution qexec) {
		Model sparqlModel = ModelFactory.createDefaultModel();
		Graph	graph = sparqlModel.getGraph();

		try {
			ResultSet results = qexec.execSelect();

			while(results.hasNext()) {
				QuerySolution sol = results.nextSolution();
				Iterator<String> iterS = sol.varNames();
				/**
				 * Each QuerySolution is a triple
				 */
				try {
					String subject = sol.getResource("s").toString();
					String predicate = sol.getResource("p").toString();
					String object = sol.get("o").toString();

					graph.add(new Triple(
								NodeFactory.createURI(subject),
								NodeFactory.createURI(predicate),
								NodeFactory.createLiteral(object)));

				} catch(NoSuchElementException nsee) {
					logger.info("Could not index [{}] / {}: Query result was" +
							"not a triple",	sol.toString(), nsee.toString());
				}
			}
		} catch(Exception e) {
			logger.info("Encountered a [{}] when quering the endpoint", e.toString());
		} finally { qexec.close(); }

		BulkRequestBuilder bulkRequest = client.prepareBulk();
		addModelToES(sparqlModel, bulkRequest);
	}

	private void harvestWithConstruct(QueryExecution qexec) {
		Model sparqlModel = ModelFactory.createDefaultModel();
		try {
			qexec.execConstruct(sparqlModel);
		} catch (Exception e) {
			logger.info("Encountered a [{}] when quering the endpoint", e.toString());
		} finally { qexec.close(); }

		BulkRequestBuilder bulkRequest = client.prepareBulk();
		addModelToES(sparqlModel, bulkRequest);
	}

	private void harvestFromEndpoint() {
		try {
			Query query = QueryFactory.create(rdfQuery);
			QueryExecution qexec = QueryExecutionFactory.sparqlService(
					rdfEndpoint,
					query);
			switch(rdfQueryType) {
				case 0: harvestWithConstruct(qexec);
								break;
				case 1: harvestWithSelect(qexec);
								break;
				case 2: //TODO implement harvestWithDescribe
								break;
				default: break;
			}

		} catch (QueryParseException qpe) {
			logger.info(
					"Could not parse [{}]. Please provide a relevant query {}",
					rdfQuery, qpe);
		}
	}

	private void harvestFromDumps() {
		for(String url:rdfUrls) {
			if(url.isEmpty()) continue;

			logger.info("Harvesting url [{}]", url);

			Model model = ModelFactory.createDefaultModel();
			RDFDataMgr.read(model, url.trim(), RDFLanguages.RDFXML);
			BulkRequestBuilder bulkRequest = client.prepareBulk();

			addModelToES(model, bulkRequest);
		}
	}

	/**
	 * Index all the resources in a Jena Model to ES
	 *
	 * @param model the model to index
	 * @param bulkRequest a BulkRequestBuilder
	 */
	private void addModelToES(Model model, BulkRequestBuilder bulkRequest) {
		HashSet<Property> properties = new HashSet<Property>();

		StmtIterator iter = model.listStatements();

		while(iter.hasNext()) {
			Statement st = iter.nextStatement();
			Property prop = st.getPredicate();
			if(!hasList
					|| (rdfListType && rdfPropList.contains(prop.toString()))
					|| (!rdfListType && !rdfPropList.contains(prop.toString()))
					|| (normalizeProp.containsKey(prop.toString()))) {
				properties.add(prop);
			}
		}

		ResIterator rsiter = model.listSubjects();

		while(rsiter.hasNext()){

			Resource rs = rsiter.nextResource();
			Map<String, ArrayList<String>> jsonMap = new HashMap<String,
				ArrayList<String>>();
			ArrayList<String> results = new ArrayList<String>();
			if(addUriForResource) {
				results.add("\"" + rs.toString() + "\"");
				jsonMap.put(
						"http://www.w3.org/1999/02/22-rdf-syntax-ns#about",
						results);
			}

			Set<String> rdfLanguages = new HashSet<String>();

			for(Property prop: properties) {
				NodeIterator niter = model.listObjectsOfProperty(rs,prop);
				if(niter.hasNext()) {
					results = new ArrayList<String>();
					String lang = "";
					String currValue = "";

					while(niter.hasNext()) {
						RDFNode node = niter.next();
						currValue = getStringForResult(node);
						if(addLanguage){
							try {
								lang = node.asLiteral().getLanguage();
								if(!lang.isEmpty()) {
									rdfLanguages.add("\"" + lang + "\"");
								}
							} catch (Exception e) {}
						}
						String shortValue = currValue.substring(1,currValue.length() - 1);

						if((hasWhiteMap && whiteMap.containsKey(prop.toString()) &&
								!whiteMap.get(prop.toString()).contains(shortValue)) ||
							 (hasBlackMap && blackMap.containsKey(prop.toString()) &&
								blackMap.get(prop.toString()).contains(shortValue))) {
								continue;
						} else {
							if(willNormalizeObj && normalizeObj.containsKey(shortValue)) {
								results.add("\"" + normalizeObj.get(shortValue) + "\"");
							} else {
									results.add(currValue);
							}
						}
					}

					String property, value;

					if(!results.isEmpty()) {
						if(willNormalizeProp &&	normalizeProp.containsKey(prop.toString())) {
							property = normalizeProp.get(prop.toString());
							if(jsonMap.containsKey(property)) {
								results.addAll(jsonMap.get(property));
								jsonMap.put(property, results);
							} else {
								jsonMap.put(property, results);
							}
						} else {
							property = prop.toString();
							jsonMap.put(property,results);
						}
					}
				}
			}
			if(addLanguage) {
				if(rdfLanguages.isEmpty() && !language.isEmpty())
					rdfLanguages.add(language);
				if(!rdfLanguages.isEmpty())
					jsonMap.put("language", new ArrayList<String>(rdfLanguages));
			}

			bulkRequest.add(client.prepareIndex(indexName, typeName, rs.toString())
				.setSource(mapToString(jsonMap)));
			BulkResponse bulkResponse = bulkRequest.execute().actionGet();
		}
	}

	private String mapToString(Map<String, ArrayList<String>> map) {
		StringBuffer result = new StringBuffer("{");
		for(Map.Entry<String, ArrayList<String>> entry : map.entrySet()) {
			ArrayList<String> value = entry.getValue();
			if(value.size() == 1)
				result.append("\"" + entry.getKey() + "\" : " + value.get(0) + ",\n");
			else
				result.append("\"" + entry.getKey() + "\" : " + value.toString() + ",\n");
		}
		result.setCharAt(result.length() - 2, '}');
		return result.toString();
	}

	private String getStringForResult(RDFNode node) {
		String result = "";
		boolean quote = false;

		if(node.isLiteral()) {
			Object literalValue = node.asLiteral().getValue();
			try {
				Class literalJavaClass = node.asLiteral()
					.getDatatype()
					.getJavaClass();

				if(literalJavaClass.equals(Boolean.class)
						|| literalJavaClass.equals(Byte.class)
						|| literalJavaClass.equals(Double.class)
						|| literalJavaClass.equals(Float.class)
						|| literalJavaClass.equals(Integer.class)
						|| literalJavaClass.equals(Long.class)
						|| literalJavaClass.equals(Short.class)) {

					result += literalValue;
				}	else {
					result =	EEASettings.parseForJson(
							node.asLiteral().getLexicalForm());
					quote = true;
				}
			} catch (java.lang.NullPointerException npe) {
				result = EEASettings.parseForJson(
						node.asLiteral().getLexicalForm());
				quote = true;
			}

		} else if(node.isResource()) {
			result = node.asResource().getURI();
			if(toDescribeURIs) {
				result = getLabelForUri(result);
			}
			quote = true;
		}
		if(quote) {
			result = "\"" + result + "\"";
		}
		return result;
	}


  private String getLabelForUri(String uri) {
		String result = "";
		for(String prop:uriDescriptionList) {
			String innerQuery = "SELECT ?r WHERE {<" + uri + "> <" +
				prop + "> ?r } LIMIT 1";

			try {
				Query query = QueryFactory.create(innerQuery);
				QueryExecution qexec = QueryExecutionFactory.sparqlService(
						rdfEndpoint,
						query);
				boolean keepTrying = true;
				while(keepTrying) {
					keepTrying = false;
					try {
						ResultSet results = qexec.execSelect();

						if(results.hasNext()) {
							QuerySolution sol = results.nextSolution();
							result = EEASettings.parseForJson(
									sol.getLiteral("r").getLexicalForm());
							if(!result.isEmpty())
								return result;
						}
					} catch(Exception e){
						keepTrying = true;
					}finally { qexec.close();}
				}
			} catch (QueryParseException qpe) {
			}
		}
		return uri;
	}

	@Deprecated
	private void delay(String reason, String url) {
		int time = 1000;
		if(!url.isEmpty()) {
			logger.info("Info: {}, waiting for url [{}] ", reason, url);
		}
		else {
			logger.info("Info: {}", reason);
			time = 2000;
		}

		try {
			Thread.sleep(time);
		} catch (InterruptedException e) {}
	}
}
