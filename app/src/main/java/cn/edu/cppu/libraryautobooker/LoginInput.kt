package cn.edu.cppu.libraryautobooker

object LoginInput {
    fun error(username: String, password: String): String? = when {
        username.isBlank() || password.isEmpty() -> "请填写账号和密码"
        (username + password).any { it == '\n' || it == '\r' } -> "账号密码不能包含换行，请检查复制的内容"
        else -> null
    }
}
