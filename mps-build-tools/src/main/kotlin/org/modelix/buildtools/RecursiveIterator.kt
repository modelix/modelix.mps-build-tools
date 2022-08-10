package org.modelix.buildtools

abstract class RecursiveIterator<T>(root: Iterator<T>) : Iterator<T> {
    private val seen: MutableSet<T> = HashSet()
    private val stack: ArrayDeque<Iterator<T>> = ArrayDeque()
    private var next: T? = null

    init {
        stack.add(root)
        next = nextElement()
    }

    protected fun nextElement(): T? {
        while (!stack.isEmpty()) {
            val top = stack.last()
            if (top.hasNext()) {
                val next = top.next()
                if (!seen.add(next)) {
                    continue
                }

                stack.addLast(children(next))
                return next
            }
            stack.removeLast()
        }
        return null
    }

    override fun hasNext(): Boolean {
        return next != null
    }

    override fun next(): T {
        val result = next ?: throw NoSuchElementException()
        next = nextElement()
        return result
    }

    protected abstract fun children(node: T): Iterator<T>
}
