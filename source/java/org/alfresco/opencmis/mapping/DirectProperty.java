package org.alfresco.opencmis.mapping;

import java.io.Serializable;
import java.util.List;

import org.alfresco.opencmis.CMISConnector;
import org.alfresco.opencmis.CMISNodeInfoImpl;
import org.alfresco.opencmis.dictionary.CMISNodeInfo;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.namespace.QName;

/**
 * A simple 1-1 property mapping from a CMIS property name to an alfresco property
 * 
 * @author florian.mueller
 */
public class DirectProperty extends AbstractProperty
{
    private QName alfrescoName;

    /**
     * Construct
     */
    public DirectProperty(ServiceRegistry serviceRegistry, CMISConnector connector, String propertyName,
            QName alfrescoName)
    {
        super(serviceRegistry, connector, propertyName);
        this.alfrescoName = alfrescoName;
    }

    public QName getMappedProperty()
    {
        return alfrescoName;
    }

    public Serializable getValueInternal(CMISNodeInfo nodeInfo)
    {
        if (nodeInfo.getType() == null)
        {
            // Invalid node
            return null;
        }
        
        if (nodeInfo.getNodeRef() != null)
        {
            Serializable result = nodeInfo.getNodeProps().get(alfrescoName);

            /* MNT-10548 fix */
            if (result instanceof List)
            {
                @SuppressWarnings("unchecked")
                List<Object> resultList = (List<Object>)result;
                for (int index = 0; index < resultList.size(); index++)
                {
                    Object element = resultList.get(index);
                    if (element instanceof NodeRef)
                    {
                    	NodeRef nodeRef = (NodeRef)element;
                        resultList.set(index, nodeRef.getId());
                    }
                }
            }
            
            return result;
        }
        else if (nodeInfo.getAssociationRef() != null)
        {
            return getServiceRegistry().getNodeService().getProperty(
                    nodeInfo.getAssociationRef().getSourceRef(),
                    alfrescoName);
        }

        return null;
    }
}
