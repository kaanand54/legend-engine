// Copyright 2023 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.engine.language.pure.grammar.test;

import org.eclipse.collections.api.tuple.Pair;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.protocol.pure.m3.SourceInformation;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.pure.generated.Root_meta_external_store_relational_runtime_RelationalDatabaseConnection;
import org.finos.legend.pure.generated.Root_meta_pure_alloy_connections_alloy_authentication_SnowflakePublicAuthenticationStrategy;
import org.finos.legend.pure.generated.Root_meta_pure_alloy_connections_alloy_specification_SnowflakeDatasourceSpecification;
import org.junit.Assert;
import org.junit.Test;

import static org.finos.legend.engine.language.pure.compiler.test.TestCompilationFromGrammar.TestCompilationFromGrammarTestSuite.test;

public class TestSnowflakeConnectionGrammarCompiler
{
    @Test
    public void testSnowflakeConnectionPropertiesPropagatedToCompiledGraph()
    {
        Pair<PureModelContextData, PureModel> result = test(TestRelationalCompilationFromGrammar.DB_INC +
                "###Connection\n" +
                "RelationalDatabaseConnection simple::StaticConnection\n" +
                "{\n" +
                "  store: model::relational::tests::dbInc;\n" +
                "  type: Snowflake;\n" +
                "  specification: Snowflake\n" +
                "  {\n" +
                "    name: 'test';\n" +
                "    account: 'account';\n" +
                "    warehouse: 'warehouseName';\n" +
                "    region: 'us-east2';\n" +
                "    proxyHost: 'sampleHost';\n" +
                "    proxyPort: 'samplePort';\n" +
                "    nonProxyHosts: 'sample';\n" +
                "    accountType: MultiTenant;\n" +
                "    organization: 'sampleOrganization';\n" +
                "    role: 'DB_ROLE_123';\n" +
                "  };\n" +
                "  auth: SnowflakePublic\n" +
                "  {" +
                "       publicUserName: 'name';\n" +
                "       privateKeyVaultReference: 'privateKey';\n" +
                "       passPhraseVaultReference: 'passPhrase';\n" +
                "  };\n" +
                "}\n");


        Root_meta_external_store_relational_runtime_RelationalDatabaseConnection connection = (Root_meta_external_store_relational_runtime_RelationalDatabaseConnection) result.getTwo().getConnection("simple::StaticConnection", SourceInformation.getUnknownSourceInformation());

        Root_meta_pure_alloy_connections_alloy_specification_SnowflakeDatasourceSpecification specification = (Root_meta_pure_alloy_connections_alloy_specification_SnowflakeDatasourceSpecification) connection._datasourceSpecification();

        Assert.assertEquals("test", specification._databaseName());
        Assert.assertEquals("account", specification._accountName());
        Assert.assertEquals("warehouseName", specification._warehouseName());
        Assert.assertEquals("us-east2", specification._region());
        Assert.assertEquals("sampleHost", specification._proxyHost());
        Assert.assertEquals("samplePort", specification._proxyPort());
        Assert.assertEquals("sample", specification._nonProxyHosts());
        Assert.assertEquals(result.getTwo().getEnumValue("meta::pure::alloy::connections::alloy::specification::SnowflakeAccountType", "MultiTenant"), specification._accountType());
        Assert.assertEquals("sampleOrganization", specification._organization());
        Assert.assertEquals("DB_ROLE_123", specification._role());


        Root_meta_pure_alloy_connections_alloy_authentication_SnowflakePublicAuthenticationStrategy authenticationStrategy = (Root_meta_pure_alloy_connections_alloy_authentication_SnowflakePublicAuthenticationStrategy) connection._authenticationStrategy();

        Assert.assertEquals("name", authenticationStrategy._publicUserName());
        Assert.assertEquals("privateKey", authenticationStrategy._privateKeyVaultReference());
        Assert.assertEquals("passPhrase", authenticationStrategy._passPhraseVaultReference());

    }

    private String getConnectionString(String tempTableDb, String tempTableSchema)
    {
        String tempTableDbSpec = tempTableDb == null ? "" : "    tempTableDb: '" + tempTableDb + "';\n";
        String tempTableSchemaSpec = tempTableSchema == null ? "" : "    tempTableSchema: '" + tempTableSchema + "';\n";
        String connString = "###Connection\n" +
                "RelationalDatabaseConnection simple::StaticConnection\n" +
                "{\n" +
                "  store: model::relational::tests::dbInc;\n" +
                "  type: Snowflake;\n" +
                "  specification: Snowflake\n" +
                "  {\n" +
                "    name: 'test';\n" +
                "    account: 'account';\n" +
                "    warehouse: 'warehouseName';\n" +
                "    region: 'us-east2';\n" +
                "    proxyHost: 'sampleHost';\n" +
                "    proxyPort: 'samplePort';\n" +
                "    nonProxyHosts: 'sample';\n" +
                "    accountType: MultiTenant;\n" +
                "    organization: 'sampleOrganization';\n" +
                "    role: 'DB_ROLE_123';\n" +
                tempTableDbSpec +
                tempTableSchemaSpec +
                "  };\n" +
                "  auth: SnowflakePublic\n" +
                "  {" +
                "       publicUserName: 'name';\n" +
                "       privateKeyVaultReference: 'privateKey';\n" +
                "       passPhraseVaultReference: 'passPhrase';\n" +
                "  };\n" +
                "}\n";

        return connString;
    }

    @Test
    public void testSnowflakeConnectionWithTempTableSpecPropagatedToCompiledGraph()
    {
        Pair<PureModelContextData, PureModel> result = test(TestRelationalCompilationFromGrammar.DB_INC +
                getConnectionString("temp_table_db", "temp_table_schema")
        );


        Root_meta_external_store_relational_runtime_RelationalDatabaseConnection connection = (Root_meta_external_store_relational_runtime_RelationalDatabaseConnection) result.getTwo().getConnection("simple::StaticConnection", SourceInformation.getUnknownSourceInformation());

        Root_meta_pure_alloy_connections_alloy_specification_SnowflakeDatasourceSpecification specification = (Root_meta_pure_alloy_connections_alloy_specification_SnowflakeDatasourceSpecification) connection._datasourceSpecification();

        Assert.assertEquals("test", specification._databaseName());
        Assert.assertEquals("account", specification._accountName());
        Assert.assertEquals("warehouseName", specification._warehouseName());
        Assert.assertEquals("us-east2", specification._region());
        Assert.assertEquals("sampleHost", specification._proxyHost());
        Assert.assertEquals("samplePort", specification._proxyPort());
        Assert.assertEquals("sample", specification._nonProxyHosts());
        Assert.assertEquals(result.getTwo().getEnumValue("meta::pure::alloy::connections::alloy::specification::SnowflakeAccountType", "MultiTenant"), specification._accountType());
        Assert.assertEquals("sampleOrganization", specification._organization());
        Assert.assertEquals("DB_ROLE_123", specification._role());
        Assert.assertEquals("temp_table_db", specification._tempTableDb());
        Assert.assertEquals("temp_table_schema", specification._tempTableSchema());


        Root_meta_pure_alloy_connections_alloy_authentication_SnowflakePublicAuthenticationStrategy authenticationStrategy = (Root_meta_pure_alloy_connections_alloy_authentication_SnowflakePublicAuthenticationStrategy) connection._authenticationStrategy();

        Assert.assertEquals("name", authenticationStrategy._publicUserName());
        Assert.assertEquals("privateKey", authenticationStrategy._privateKeyVaultReference());
        Assert.assertEquals("passPhrase", authenticationStrategy._passPhraseVaultReference());

    }

    @Test
    public void testSnowflakeConnectionTempTableDbPresentAndSchemaAbsent()
    {
        test(TestRelationalCompilationFromGrammar.DB_INC + getConnectionString("temp_table_db", null),
                "COMPILATION error at [65:1-88:1]: Error in 'simple::StaticConnection': One of Database name and schema name for temp tables is missing. Please specify both tempTableDb and tempTableSchema");
    }

    @Test
    public void testSnowflakeConnectionTempTableSchemaPresentAndDbAbsent()
    {
        test(TestRelationalCompilationFromGrammar.DB_INC + getConnectionString(null, "temp_table_schema"),
                "COMPILATION error at [65:1-88:1]: Error in 'simple::StaticConnection': One of Database name and schema name for temp tables is missing. Please specify both tempTableDb and tempTableSchema");
    }

    @Test
    public void testSnowflakeConnectionPropertiesLocalMode()
    {
        Pair<PureModelContextData, PureModel> result = test(TestRelationalCompilationFromGrammar.DB_INC +
                "###Connection\n" +
                "RelationalDatabaseConnection simple::StaticConnection\n" +
                "{\n" +
                "  store: model::relational::tests::dbInc;\n" +
                "  type: Snowflake;\n" +
                "  mode: local;\n" +
                "}\n");


        Root_meta_external_store_relational_runtime_RelationalDatabaseConnection connection = (Root_meta_external_store_relational_runtime_RelationalDatabaseConnection) result.getTwo().getConnection("simple::StaticConnection", SourceInformation.getUnknownSourceInformation());
        Root_meta_pure_alloy_connections_alloy_specification_SnowflakeDatasourceSpecification specification = (Root_meta_pure_alloy_connections_alloy_specification_SnowflakeDatasourceSpecification) connection._datasourceSpecification();

        Assert.assertEquals("legend-local-snowflake-databaseName-model-relational-tests-dbInc", specification._databaseName());
        Assert.assertEquals("legend-local-snowflake-accountName-model-relational-tests-dbInc", specification._accountName());
        Assert.assertEquals("legend-local-snowflake-warehouseName-model-relational-tests-dbInc", specification._warehouseName());
        Assert.assertEquals("legend-local-snowflake-region-model-relational-tests-dbInc", specification._region());
        Assert.assertEquals("legend-local-snowflake-role-model-relational-tests-dbInc", specification._role());

        Root_meta_pure_alloy_connections_alloy_authentication_SnowflakePublicAuthenticationStrategy authenticationStrategy = (Root_meta_pure_alloy_connections_alloy_authentication_SnowflakePublicAuthenticationStrategy) connection._authenticationStrategy();

        Assert.assertEquals("legend-local-snowflake-publicuserName-model-relational-tests-dbInc", authenticationStrategy._publicUserName());
        Assert.assertEquals("legend-local-snowflake-privateKeyVaultReference-model-relational-tests-dbInc", authenticationStrategy._privateKeyVaultReference());
        Assert.assertEquals("legend-local-snowflake-passphraseVaultReference-model-relational-tests-dbInc", authenticationStrategy._passPhraseVaultReference());
    }

    @Test
    public void testSnowflakeConnectionWithOAuthAuthentication()
    {
        test("###Connection\n" +
                "RelationalDatabaseConnection meta::mySimpleConnection\n" +
                "{\n" +
                "  store: model::firm::Person;\n" +
                "  type: Snowflake;\n" +
                "  quoteIdentifiers: true;\n" +
                "  specification: Snowflake\n" +
                "      {\n" +
                "           name: 'dbName';\n" +
                "           account: 'account';\n" +
                "           warehouse: 'warehouse';\n" +
                "           region: 'region';\n" +
                "      };\n" +
                "  auth: OAuth\n" +
                "  {\n" +
                "    oauthKey: 'oauthKey';\n" +
                "    scopeName: 'scopeName';\n" +
                "  };\n" +
                "}\n");
    }
}
