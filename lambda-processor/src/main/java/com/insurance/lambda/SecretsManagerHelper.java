package com.insurance.lambda;

import org.json.JSONObject;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/**
 * BONUS ACTIVITY: AWS Secrets Manager integration.
 *
 * Fetches the RDS credentials secret at invocation time instead of reading
 * them from plaintext environment variables. The secret is expected to be
 * stored as a JSON string with keys: username, password, host, port, dbname.
 *
 * Only the Lambda execution role needs secretsmanager:GetSecretValue on
 * this one secret ARN - least privilege applies here too.
 */
public class SecretsManagerHelper {

    private final SecretsManagerClient client = SecretsManagerClient.builder().build();

    public DbCredentials fetchDbCredentials(String secretArn) {
        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretArn)
                .build();

        GetSecretValueResponse response = client.getSecretValue(request);
        JSONObject json = new JSONObject(response.secretString());

        // NOTE: per the assignment's bonus requirement, we confirm retrieval
        // in the CloudWatch console. We deliberately do NOT print the password -
        // only non-sensitive fields - even in a training exercise, that habit
        // is worth keeping.
        System.out.println("Secrets Manager: retrieved DB credentials for user '"
                + json.getString("username") + "' from secret " + secretArn);

        return new DbCredentials(
                json.getString("host"),
                json.getInt("port"),
                json.getString("dbname"),
                json.getString("username"),
                json.getString("password")
        );
    }

    public record DbCredentials(String host, int port, String dbName, String username, String password) {
        public String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + dbName
                    + "?useSSL=true&serverTimezone=UTC";
        }
    }
}
