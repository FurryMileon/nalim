# Nalim v2

Nalim связывает Java-метод с короткой функцией на C или Rust через JVMCI в HotSpot. Выигрыш появляется там, где Java часто вызывает нативный код. Сам по себе `nalim.jar` не меняет ни одного игрового тика: на сервере библиотекой должен пользоваться мод, например RustMi.

**[Скачать nalim.jar 1.2.5](https://raw.githubusercontent.com/FurryMileon/nalim/84fb642e623218f05c2a04439d5153439e906cef/nalim.jar)** · [English guide & ABI reference for this jar](https://github.com/FurryMileon/nalim/blob/84fb642e623218f05c2a04439d5153439e906cef/README.md)

- **Держите сервер Minecraft?** [Включите Nalim в RustMi и проверьте две строки в логе.](#сервер-с-rustmi) Править Java-код не нужно.
- **Пишете мод или библиотеку?** [Выберите способ вызова.](#интеграция-в-java) Для массива выгодно сократить число вызовов. Для некоторых коротких функций можно убрать нативный вызов из горячего цикла совсем.
- **Нужны цифры?** [Замеры и команды для сравнения на вашем железе.](#замеры)

## Сервер с RustMi

Инструкция для **Minecraft 26.3, Fabric, [RustMi](https://github.com/DUzzL/RustMi) и Linux x86-64** (Intel или AMD). Начните с обычного HotSpot **JDK 25** и стандартного сборщика G1. На других системах не форсируйте Nalim: у RustMi есть запасной backend FFM.

1. Остановите сервер. Оставьте установленный `rustmi-*.jar` в `mods/`. RustMi уже включает Nalim. Если в списке модов Fabric указана версия старее `1.2.5-patched-autoinline`, положите [JAR из этой ветки](https://raw.githubusercontent.com/FurryMileon/nalim/84fb642e623218f05c2a04439d5153439e906cef/nalim.jar) в `mods/`. Если там уже есть отдельная старая копия Nalim, **замените её**. Распаковывать RustMi или ставить `-javaagent` не нужно.
2. В каталоге сервера запустите команду ниже. В панели хостинга добавьте параметры JVM **до `-jar`**, сохранив свои настройки памяти. **На JDK 26** туда же добавьте `-XX:-UseCodeCacheFlushing`: иначе RustMi не выберет Nalim.

   ```sh
   java --add-modules=jdk.internal.vm.ci --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -jar fabric-server-launch.jar nogui
   ```

3. Проверьте `logs/latest.log`. Нужны **обе** строки: первая показывает, какой JAR выбрал Fabric, вторая — чем RustMi действительно вызывает Rust.

   ```text
   nalim 1.2.5-patched-autoinline
   RustMI native backend: Nalim (JVMCI)
   ```

`/rustmi stats` тоже показывает backend. Если в логе `FFM (…)`, причина указана в скобках. На отдельном тестовом сервере с указанными выше Linux и JDK можно добавить `-Drustmi.backend=nalim`: тогда ошибка запуска не скроется за переключением на FFM. Для возврата к FFM, в том числе на обычных JDK 27 и 28 EA без JVMCI, уберите параметры Nalim и запустите:

```sh
java --enable-native-access=ALL-UNNAMED -Drustmi.backend=ffm -jar fabric-server-launch.jar nogui
```

**Как узнать, ускорился ли сервер.** Возьмите одну копию мира и один сценарий нагрузки. Между запусками восстанавливайте эту копию, прогревайте сервер одинаково и чередуйте варианты: A/B, затем B/A. Для сравнения версий меняйте только JAR, оставляя backend `Nalim` в обоих запусках. Для сравнения Nalim с FFM, наоборот, оставьте тот же JAR и меняйте backend. На тестовой копии `/tick sprint 3000` покажет среднее время тика за один прогон. Для игрового сервера смотрите также медиану и p95 **MSPT** (миллисекунд на тик): p95 показывает, сколько времени хватило для 95% тиков. Показатель 20 TPS не различает серверы, которым на тик нужно 5 и 45 мс.

## Интеграция в Java

**Если функция обрабатывает данные, начните с размера порции.** Вместо нативного `load` на каждый элемент сделайте один вызов на небольшой массив или его часть. Готовый пример: [`nalimSumHeap(long[] p, int n)`](test/BenchMemAccess.java) вызывает [`mem_sum_longs(const int64_t* p, int32_t n)`](test/nalim_mem.c) без промежуточной копии. Загрузите библиотеку и вызовите `Linker.linkClass(...)` один раз при инициализации класса. Сравните с простым циклом на Java: для мелкой работы он может оказаться быстрее.

Проверяйте `p != null` и `0 <= n <= p.length` **на стороне Java**. Нативная функция не должна сохранять адрес массива после возврата, блокироваться или вызывать Java. Для долгого вычисления, I/O или обратных вызовов используйте JNI или FFM. Сигнатура обязана совпадать с реальным C ABI; для Rust нужен экспорт с `extern "C"`.

**Если часто вызывается одна короткая арифметическая функция**, на Linux x86-64 попробуйте `Linker.findIntrinsic`. Nalim читает машинный код символа и ищет точный Java-эквивалент. Одного совпадения имени недостаточно. Подходят некоторые функции с одним или двумя `int` либо `long`, с таким же типом результата. Если эквивалент найден, HotSpot может встроить его в вызывающий цикл. Обычный `@Link` сам на `MethodHandle` не переключается.

Пример использует `raw_add` из [`test/nalim_bench.c`](test/nalim_bench.c). Скачайте исходники и сохраните класс ниже как `Demo.java` в каталоге `nalim`:

```sh
git clone --branch v2 https://github.com/FurryMileon/nalim.git
cd nalim
```

```java
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import one.nalim.Link;
import one.nalim.Linker;

public final class Demo {
    @Link(name = "raw_add")
    private static native int nativeAdd(int x, int y);

    private static final MethodHandle ADD;
    static {
        Linker.loadLibrary("nalim_bench");
        ADD = Linker.findIntrinsic("raw_add",
                MethodType.methodType(int.class, int.class, int.class)).orElse(null);
        if (ADD == null) Linker.linkClass(Demo.class);
    }

    static int add(int x, int y) throws Throwable {
        return ADD == null ? nativeAdd(x, y) : (int) ADD.invokeExact(x, y);
    }

    public static void main(String[] args) throws Throwable {
        System.out.println((ADD == null ? "@Link" : "intrinsic") + ": " + add(19, 23));
    }
}
```

Из корня репозитория, на **Linux x86-64 с JDK 25** и компилятором C:

```sh
cc -O2 -shared -fPIC test/nalim_bench.c -o libnalim_bench.so
javac -cp nalim.jar Demo.java
java --add-modules=jdk.internal.vm.ci --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Djava.library.path=. -cp nalim.jar:. Demo
```

`intrinsic: 42` означает, что Nalim распознал функцию. `@Link: 42` — что сработал обычный нативный вызов: это нормально, например, при другой генерации кода компилятором C. Чтобы проверить второй путь, добавьте `-Dnalim.noinline=true` перед `-cp` в последней команде; для замеров быстрого пути уберите его. Ищите `MethodHandle` **один раз**, храните в `static final`, вызывайте через `invokeExact` с примитивными типами. Не подменяйте машинный код загруженной функции, пока используете её Java-эквивалент. Обоим путям в этом примере нужен JAR v2: на v1 самого API `findIntrinsic` нет.

## Замеры

[`BenchLeafCalls`](test/BenchLeafCalls.java): Linux x86-64, Ryzen AI 9 365, OpenJDK 25.0.5.0.3, G1, одно закреплённое ядро, восемь пар свежих JVM с чередованием порядка. Медианы по процессам, **наносекунды на элемент прогретого цикла**; меньше лучше. На `main` у `MethodHandle` нет Java-эквивалента, и он вызывает тот же нативный метод.

| Операция в цикле | `main` (`d367a5e`) | v2 (`1.2.5`) |
|---|---:|---:|
| Сложение: обычный `@Link` | 1.1150 | 1.1165 |
| Сложение: `static final` + `invokeExact` | 1.1195 | 0.0625 |
| Модуль числа: обычный `@Link` | 0.9022 | 0.9018 |
| Модуль числа: `static final` + `invokeExact` | 0.9030 | 0.0635 |

В таком цикле v2 обработала сложения примерно в **17,9 раза**, а модули числа — в **14,2 раза** быстрее по пути `invokeExact`. Это пропускная способность цикла после оптимизации JIT, **не** задержка отдельного вызова и не множитель TPS. В строках с обычным `@Link` разницы практически нет.

Повторить на Linux x86-64: нужны полный клон v2 с историей, JDK 25, `cc`, Python 3 и `taskset`. Запустите из корня репозитория. Скрипт сам выберет доступное ядро; для выбора вручную есть `--cpu` (в таблице — `--cpu 2`).

```sh
(
  set -e
  baseline=$(mktemp -d)
  trap 'rm -rf -- "$baseline"' EXIT
  git show d367a5e:nalim.jar > "$baseline/main.jar"
  python3 test/bench-link-paths.py "$baseline/main.jar" nalim.jar --workload leaves --pairs 8
)
```

В выводе проверьте `raw_add.intrinsic=1` и `raw_abs.intrinsic=1` для `after`. Скрипт проверяет контрольные суммы. Чтобы заодно измерить обычные пути связывания, замените `--workload leaves` на `--workload paths leaves`.

**Что получилось ближе к серверу.** В [тесте сигнатур RustMi](test/BenchRustMiAbi.java) на JDK 26 время вызова `spawncap` упало с 1,369 до 1,199 нс; у `collision` почти не изменилось (2,142 → 2,141 нс), у `distance` слегка выросло (2,084 → 2,097 нс). На реальном тестовом сервере RustMi 1.2.1 и Nalim 1.2.5 прошли на Java 26 проверку столкновений: 8 278 065 совпадений с ванильной логикой, 0 расхождений. Это проверка результата. В более раннем сравнении **серверного** времени тика (Nalim 1.2.1 против 1.2.2) новая версия выиграла только в двух из четырёх пар. Устойчивого выигрыша по MSPT от одной замены JAR эти данные не показывают.

## Платформы и сборка

- **Библиотека Nalim:** Linux и macOS на x86-64 и AArch64; Windows на x86-64. `amd64` включает и Intel, и AMD. Windows on Arm не поддерживается. [Тесты нативного ABI](.github/workflows/platforms.yml) запускаются на всех пяти сочетаниях с JDK 21, 25 и 26. Это шире, чем набор платформ, где **RustMi** автоматически выбирает Nalim.
- **Java:** требуется HotSpot с модулем `jdk.internal.vm.ci` (проверьте `java --list-modules`). На JDK 21 к параметрам из примера нужны `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI`; на JDK 26 — `-XX:-UseCodeCacheFlushing`. На 11 и 17 проверяется только базовый нативный вызов на Linux x86-64. В обычной JDK 27 [JVMCI удалён](https://bugs.openjdk.org/browse/JDK-8385142); в проверенной JDK 28 EA его тоже нет. Добавить отсутствующий модуль флагом нельзя.
- **Сборка из исходников:** `sh build.sh` в корне создаёт `nalim.jar`. Для проверки готового JAR: `bash test/check-platform.sh nalim.jar` (JDK 21/25/26 и компилятор C; на Windows — Git Bash и GCC). Нужна среда запуска с уже заданными параметрами JVM — см. [`build-runtime.sh`](build-runtime.sh) или [`build-runtime.ps1`](build-runtime.ps1).

Основано на [Nalim Андрея Пангина](https://github.com/apangin/nalim). [Лицензия GPL-2.0-or-later](LICENSE).
