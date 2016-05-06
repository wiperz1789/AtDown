package stdlib.math.crypto.generators;

import java.math.BigInteger;

import stdlib.math.crypto.AsymmetricCipherKeyPair;
import stdlib.math.crypto.AsymmetricCipherKeyPairGenerator;
import stdlib.math.crypto.KeyGenerationParameters;
import stdlib.math.crypto.params.DHKeyGenerationParameters;
import stdlib.math.crypto.params.DHParameters;
import stdlib.math.crypto.params.DHPrivateKeyParameters;
import stdlib.math.crypto.params.DHPublicKeyParameters;

/**
 * a basic Diffie-Helman key pair generator.
 *
 * This generates keys consistent for use with the basic algorithm for
 * Diffie-Helman.
 */
public class DHBasicKeyPairGenerator
    implements AsymmetricCipherKeyPairGenerator
{
    private DHKeyGenerationParameters param;

    public void init(
        KeyGenerationParameters param)
    {
        this.param = (DHKeyGenerationParameters)param;
    }

    public AsymmetricCipherKeyPair generateKeyPair()
    {
        BigInteger      p, g, x, y;
        int             qLength = param.getStrength() - 1;
        DHParameters    dhParams = param.getParameters();

        p = dhParams.getP();
        g = dhParams.getG();
   
        //
        // calculate the private key
        //
		x = new BigInteger(qLength, param.getRandom());
		
        //
        // calculate the public key.
        //
        y = g.modPow(x, p);

        return new AsymmetricCipherKeyPair(
                new DHPublicKeyParameters(y, dhParams),
                new DHPrivateKeyParameters(x, dhParams));
    }
}
