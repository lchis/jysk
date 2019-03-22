package com.digitalctrl.jysk.core.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import com.digitalctrl.jysk.core.workflow.impl.XMLProcessingConfiguration;
import com.adobe.cq.dam.cfm.ContentElement;
import com.adobe.cq.dam.cfm.ContentFragment;
import com.adobe.cq.dam.cfm.ContentFragmentException;
import com.adobe.cq.dam.cfm.ContentFragmentManager;
import com.adobe.cq.dam.cfm.FragmentTemplate;
import com.adobe.granite.workflow.PayloadMap;
import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;

@Component(service=WorkflowProcess.class, property = {"process.label=XML Product Mapper"}, configurationPolicy=ConfigurationPolicy.REQUIRE)
@Designate(ocd = XMLProcessingConfiguration.class)
public class XMLProcessing implements WorkflowProcess {
	
	private static final String RENDITION_TEXT_XML_MIME_TYPE = "text/xml";
	private static final String RENDITION_APPLICATION_XML_MIME_TYPE = "application/xml";
	
	private static final Logger LOGGER = LoggerFactory.getLogger(XMLProcessing.class);
	
	@Reference
	ResourceResolverFactory resourceResolverFactory;
	
	@Reference
	ContentFragmentManager contentFragmentManager;
	
	private FragmentTemplate contentFragmentTemplate;
	
	@Activate
	private XMLProcessingConfiguration config;
	
	@Activate
	public void activate() throws LoginException {
		Map<String, Object> loginParam = new HashMap<>();
		loginParam.put(ResourceResolverFactory.SUBSERVICE, "xmlProcessing");
		ResourceResolver resourceResolver = resourceResolverFactory.getServiceResourceResolver(loginParam);
		
		contentFragmentTemplate = resourceResolver.getResource(config.contentFragmentTemplate()).adaptTo(FragmentTemplate.class);
		LOGGER.info("Got content fragment template {} from {}", contentFragmentTemplate.getTitle(), config.contentFragmentTemplate());
		
		Resource parentResource = resourceResolver.getResource("/content/dam/jysk/products");
		LOGGER.info("Creating fragment under {}", parentResource.getPath());
		ContentFragment productContentFragment = createContentFragment(parentResource);
		
		Iterator<ContentElement> contentFragmentElements = productContentFragment.getElements();
		contentFragmentElements.forEachRemaining(contentElement -> {
			LOGGER.info("Content Fragment has content element {}", contentElement.getName());
		});
	}

	@Override
	public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
		
		LOGGER.info("Running xml process on {}", item.getContentPath());
		
		Asset asset;
		try {
			asset = getResourceFromItem(item, session);
		} catch (RepositoryException e) {
			LOGGER.error("Failed to get Node object from workflow item", e.getMessage());
			return;
		}
		
		if(asset == null) {
			LOGGER.warn("Got null asset object from {}", asset.getPath());
			return;
		}
		
		InputStream xmlRenditionInputStream = getXmlInputStream(asset, session);
		if(xmlRenditionInputStream == null) {
			LOGGER.warn("Failed getting xml rendition input stream from workflow item {}", item.getContentPath());
			return;
		}
		
		DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder parser;
		Document dc;
		try {
			parser = documentBuilderFactory.newDocumentBuilder();
			dc = parser.parse(xmlRenditionInputStream);
		} catch (IOException | SAXException | ParserConfigurationException e) {
			LOGGER.error("Failed generating document object from xml rendition input stream {}", xmlRenditionInputStream.toString());
			return;
		}
		
		Node rootNode = dc.getFirstChild();
		NodeList childNodes = rootNode.getChildNodes();
		int numChildNodes = childNodes.getLength();
		for(int i = 0 ; i < numChildNodes; i++) {
			Node currChildNode = childNodes.item(i);
			LOGGER.info("Node {} is {}", currChildNode.getNodeName(), currChildNode.getNodeValue());
		}
		
		ContentFragment productContentFragment = createContentFragment(asset.adaptTo(Resource.class).getParent());
	}

	private ContentFragment createContentFragment(Resource parentResource) {
		ContentFragment productContentFragment;
		try {
			productContentFragment = contentFragmentTemplate.createFragment(parentResource, "newfragment", "New Fragment");
		} catch (ContentFragmentException e) {
			LOGGER.error("Failed creating content fragment from template {}", contentFragmentTemplate.getTitle()+ " at " + parentResource.getPath(), e.getMessage());
			return null;
		}
		
		LOGGER.info("Successfully created content fragment {} at {}", productContentFragment.getName(), parentResource.getPath());
		
		return productContentFragment;
	}

	private InputStream getXmlInputStream(Asset asset, WorkflowSession session) {
		
		List<Rendition> renditions = asset.getRenditions();
		Rendition xmlRendition = null;
		for (Rendition rendition : renditions) {
			if(rendition.getMimeType().equals(RENDITION_APPLICATION_XML_MIME_TYPE) || rendition.getMimeType().equals(RENDITION_TEXT_XML_MIME_TYPE)) {
				xmlRendition = rendition;
				break;
			}
		}
		
		if(xmlRendition == null) {
			LOGGER.warn("Failed getting a valid xml rendition from asset {}", asset.getPath());
			return null;
		}
		
		LOGGER.info("XML data: {}", xmlRendition.getValueMap().get("jcr:content/jcr:data"));
		
		LOGGER.info("Found rendition {} for asset {}", xmlRendition.getPath(), asset.getPath());
		InputStream xmlRenditionInputStream = xmlRendition.getStream();
		return xmlRenditionInputStream;
	}

	private Asset getResourceFromItem(WorkItem item, WorkflowSession session) throws RepositoryException{
		WorkflowData workflowData = item.getWorkflowData();
		
		if(!workflowData.getPayloadType().equals(PayloadMap.TYPE_JCR_PATH)){
			LOGGER.warn("Failed getting resource url from payload");
			return null;
		}
		
		ResourceResolver resourceResolver = session.adaptTo(ResourceResolver.class);
		Rendition assetRendition = resourceResolver.getResource(workflowData.getPayload().toString()).adaptTo(Rendition.class);
		Asset asset = assetRendition.getAsset();
		
		LOGGER.info("Possible paths {} and {}", item.getContentPath(), workflowData.getPayload().toString());
		
		if(asset == null) {
			LOGGER.warn("Failed getting resource from payload");
			return null;
		}

		return asset;
	}	
}
