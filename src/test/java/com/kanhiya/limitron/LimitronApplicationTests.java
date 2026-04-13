package com.kanhiya.limitron;

import com.kanhiya.limitron.testsupport.RedisContainerTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LimitronApplicationTests extends RedisContainerTestBase {

	@Test
	void contextLoads() {
	}

}
