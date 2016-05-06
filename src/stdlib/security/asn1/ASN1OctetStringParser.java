package stdlib.security.asn1;

import java.io.InputStream;

public interface ASN1OctetStringParser
    extends DEREncodable
{
    public InputStream getOctetStream();
}