@Test
public void testBlankConfigWithAuth() {
    Config config = new Config();
    config.content = "";
    config.credentialsId = "some-cred-id";
    stepExecution.createNpmConfig(config); // Should succeed
}

@Test(expected = AbortException.class)
public void testBlankConfigWithoutAuth() {
    Config config = new Config();
    config.content = "";
    stepExecution.createNpmConfig(config); // Should fail
}
