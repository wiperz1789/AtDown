package stdlib.math.crypto.generators;

import java.math.BigInteger;
import java.security.SecureRandom;

import stdlib.math.crypto.AsymmetricCipherKeyPair;
import stdlib.math.crypto.AsymmetricCipherKeyPairGenerator;
import stdlib.math.crypto.KeyGenerationParameters;
import stdlib.math.crypto.params.DSAKeyGenerationParameters;
import stdlib.math.crypto.params.DSAParameters;
import stdlib.math.crypto.params.DSAPrivateKeyParameters;
import stdlib.math.crypto.params.DSAPublicKeyParameters;

/**
 * a DSA key pair generator.
 *
 * This generates DSA keys in line with the method described 
 * in FIPS 186-2.
 */
public class DSAKeyPairGenerator
    implements AsymmetricCipherKeyPairGenerator
{
    private static BigInteger ZERO = BigInteger.valueOf(0);

    private DSAKeyGenerationParameters param;

    public void init(
        KeyGenerationParameters param)
    {
        this.param = (DSAKeyGenerationParameters)param;
    }

    public AsymmetricCipherKeyPair generateKeyPair()
    {
        BigInteger      p, q, g, x, y;
        DSAParameters   dsaParams = param.getParameters();
        SecureRandom    random = param.getRandom();

        q = dsaParams.getQ();
        p = dsaParams.getP();
        g = dsaParams.getG();

        do
        {
            x = new BigInteger(160, random);
        }
        while (x.equals(ZERO)  || x.compareTo(q) >= 0);

        //
        // calculate the public key.
        //
        y = g.modPow(x, p);

        return new AsymmetricCipherKeyPair(
                new DSAPublicKeyParameters(y, dsaParams),
                new DSAPrivateKeyParameters(x, dsaParams));
    }
}
