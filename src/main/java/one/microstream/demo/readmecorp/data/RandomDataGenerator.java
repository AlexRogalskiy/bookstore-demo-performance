package one.microstream.demo.readmecorp.data;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.rapidpm.dependencies.core.logger.HasLogger;

import com.github.javafaker.Faker;

import one.microstream.persistence.types.Storer;
import one.microstream.storage.types.EmbeddedStorageManager;

final class RandomDataGenerator implements HasLogger
{
	private static class CountryData extends ArrayList<City>
	{
		Faker                     faker;
		Locale                    locale;
		List<Shop>                shops;
		Map<City, List<Customer>> people;
		
		CountryData(
			final Faker faker,
			final Locale locale
		)
		{
			super(512);
			
			this.faker   = faker;
			this.locale  = locale;
			
			this.shops = new ArrayList<>();
		}
		
		City randomCity(final Random random)
		{
			return this.get(random.nextInt(this.size()));
		}
		
		Customer randomCustomer(final Random random)
		{
			return this.randomCustomer(random, this.randomCity(random));
		}
		
		Customer randomCustomer(final Random random, final City city)
		{
			final List<Customer> peopleOfCity = this.people.get(city);
			return peopleOfCity.get(random.nextInt(peopleOfCity.size()));
		}
		
		void dispose()
		{
			this.faker = null;
			this.locale = null;
			
			this.shops.clear();
			this.shops = null;
			
			this.people.values().forEach(List::clear);
			this.people.clear();
			
			this.clear();
		}
	}
	
	private final Books.Mutable          books;
	private final List<Shop>             shops;
	private final List<Customer>         customers;
	private final Purchases.Mutable      purchases;
	private final RandomDataAmount       dataAmount;
	private final EmbeddedStorageManager storageManager;
	private final Random                 random;
	private final Faker                  faker;
	private final Set<String>            usedIsbns;
	private final List<Book>             bookList;
	
	RandomDataGenerator(
		final Books.Mutable books,
		final List<Shop> shops,
		final List<Customer> customers,
		final Purchases.Mutable purchases,
		final RandomDataAmount dataAmount,
		final EmbeddedStorageManager storageManager
	)
	{
		super();
		
		this.books          = books;
		this.shops          = shops;
		this.customers      = customers;
		this.purchases      = purchases;
		this.dataAmount     = dataAmount;
		this.storageManager = storageManager;
		
		this.random    = new Random();
		this.faker     = Faker.instance();
		this.usedIsbns = new HashSet<>(4096);
		this.bookList  = new ArrayList<>(4096);
	}
	
	DataMetrics generate()
	{
		final List<Locale> locales = this.supportedLocales();
		
		this.logger().info("+ " + locales.size() + " locales");
		
		final List<CountryData> countries = locales.parallelStream()
			.map(this::createCountry)
			.collect(toList());
		
		this.createBooks(countries);
		
		this.createShops(countries);
		
		this.createPurchases(countries);
		
		final DataMetrics metrics = DataMetrics.New(
			this.books.size(),
			countries.size(),
			this.shops.size()
		);

		this.shops.forEach(Shop::clear);
		this.usedIsbns.clear();
		this.bookList.clear();
		countries.forEach(CountryData::dispose);
		countries.clear();
		
		this.gc();
		
		return metrics;
	}
	
	private List<Locale> supportedLocales()
	{
		final List<Locale> locales = Arrays.asList(
			Locale.US,
			Locale.CANADA_FRENCH,
			Locale.GERMANY,
			Locale.FRANCE,
			Locale.UK,
			new Locale("pt", "BR"),
			new Locale("de", "AT"),
			new Locale("fr", "CH"),
			new Locale("nl", "NL"),
			new Locale("hu", "HU"),
			new Locale("pl", "PL")
		);
		
		final int maxCountries = this.dataAmount.maxCountries();
		final int max = maxCountries == -1
			? locales.size()
			: maxCountries;
		return max >= locales.size()
			? locales
			: locales.subList(0, max);
	}

	private CountryData createCountry(
		final Locale locale
	)
	{
		this.logger().info("> country " + locale.getDisplayCountry());
		
		final Faker              faker       = Faker.instance(locale);
		final Set<String>        cityNameSet = new HashSet<>();
		final Map<String, State> stateMap    = new HashMap<>();
		final Country            country     = Country.New(
			locale.getDisplayCountry(Locale.ENGLISH),
			locale.getCountry()
		);
		final CountryData        countryData = new CountryData(faker, locale);
		this.randomRange(this.dataAmount.maxCitiesPerCountry()).forEach(i ->
		{
			final com.github.javafaker.Address fakerAddress = faker.address();
			final String                       cityName     = fakerAddress.city();
			if(cityNameSet.add(cityName))
			{
				final String stateName = fakerAddress.state();
				final State  state     = stateMap.computeIfAbsent(
					stateName,
					s -> State.New(stateName, country)
				);
				countryData.add(City.New(cityName, state));
			}
		});
		
		countryData.people = new HashMap<>(countryData.size(), 1.0f);
		countryData.parallelStream().forEach(city -> {
			countryData.people.put(city, this.createCustomers(countryData, city));
		});
		
		this.logger().info(
			"+ country " + locale.getDisplayCountry() + " [" + countryData.size() + " cities, " +
				countryData.people.values().stream().mapToInt(List::size).sum() + " customers] "
		);
		
		return countryData;
	}
	
	private List<Customer> createCustomers(
		final CountryData countryData,
		final City city
	)
	{
		return this.randomRange(this.dataAmount.maxCustomersPerCity())
			.mapToObj(i -> Customer.New(
				countryData.faker.name().fullName(),
				this.createAddress(city, countryData.faker)
			))
			.collect(toList());
	}
	
	private void createBooks(
		final List<CountryData> countries
	)
	{
		final List<Genre> genres = this.createGenres();
		countries.parallelStream().forEach(country ->
		{
			this.logger().info("> books in " + country.locale.getDisplayCountry());
			
			final List<Publisher> publishers = this.createPublishers(country);
			final List<Author>    authors    = this.createAuthors(country);
			final Language        language   = Language.New(country.locale);
			final List<Book>      books      = IntStream.range(0, this.dataAmount.maxBooksPerCountry())
				.mapToObj(i -> country.faker.book().title())
				.map(title -> this.createBook(country, genres, publishers, authors, language, title))
				.collect(toList());
			
			this.books.addAll(books);
			synchronized(this.bookList)
			{
				this.bookList.addAll(books);
			}
			
			this.logger().info("+ " + books.size() + " books in "+ country.locale.getDisplayCountry());
		});

		final Storer storer = this.storageManager.createEagerStorer();
		storer.store(this.books);
		storer.commit();
	}
	
	private Book createBook(
		final CountryData country,
		final List<Genre> genres,
		final List<Publisher> publishers,
		final List<Author> authors,
		final Language language,
		final String title
	)
	{
		String isbn;
		synchronized(this.usedIsbns)
		{
			while(!this.usedIsbns.add(isbn = this.faker.code().isbn13(true)))
			{
				; // empty loop
			}
		}
		final Genre     genre     = genres.get(this.random.nextInt(genres.size()));
		final Publisher publisher = publishers.get(this.random.nextInt(publishers.size()));
		final Author    author    = authors.get(this.random.nextInt(authors.size()));
		final double    price     = this.createPrice(5.0, 25.0);
		return Book.New(isbn, title, author, genre, publisher, language, price);
	}
	
	private List<Genre> createGenres()
	{
		return this.randomRange(this.dataAmount.maxGenres())
			.mapToObj(i -> this.faker.book().genre())
			.distinct()
			.map(Genre::New)
			.collect(toList());
	}
	
	private List<Publisher> createPublishers(
		final CountryData countryData
	)
	{
		return this.randomRange(this.dataAmount.maxPublishersPerCountry())
			.mapToObj(i -> countryData.faker.book().publisher())
			.distinct()
			.map(name -> Publisher.New(name, this.createAddress(countryData.randomCity(this.random), countryData.faker)))
			.collect(toList());
	}
	
	private List<Author> createAuthors(
		final CountryData countryData
	)
	{
		return this.randomRange(this.dataAmount.maxAuthorsPerCountry())
			.mapToObj(i -> countryData.faker.book().author())
			.distinct()
			.map(name -> Author.New(name, this.createAddress(countryData.randomCity(this.random), countryData.faker)))
			.collect(toList());
	}
	
	private void createShops(
		final List<CountryData> countries
	)
	{
		countries.parallelStream().forEach(country ->
		{
			this.logger().info("> shops in " + country.locale.getDisplayCountry());

			country.forEach(
				city -> this.randomRange(this.dataAmount.maxShopsPerCity()).forEach(
					i -> country.shops.add(this.createShop(countries, country, city, i))
				)
			);

			this.logger().info("+ " + country.shops.size() + " shops in " + country.locale.getDisplayCountry());
		});
		
		countries.forEach(country -> this.shops.addAll(country.shops));
		this.storageManager.store(this.shops);
	}
	
	private Shop createShop(
		final List<CountryData> countries,
		final CountryData countryData,
		final City city,
		final int nr
	)
	{
		final String             name      = city.name() + " Shop " + nr;
		final Address            address   = this.createAddress(city, countryData.faker);
		final List<Employee>     employees = this.createEmployees(countryData, city);
		final Map<Book, Integer> inventory = this.randomRange(this.dataAmount.maxBooksPerShop())
			.mapToObj(i -> this.randomBook())
			.distinct()
			.collect(toMap(
				book -> book,
				book -> this.random.nextInt(50) + 1
			));
		return new Shop.Default(name, address, employees, new Inventory.Default(inventory));
	}
	
	private void createPurchases(
		final List<CountryData> countries
	)
	{
		final Set<Customer> customers = new HashSet<>(4096);
		
		final int           thisYear  = Year.now().getValue();
		final int           startYear = thisYear - this.randomMax(this.dataAmount.maxAgeOfShopsInYears()) + 1;
		IntStream.rangeClosed(startYear, thisYear).forEach(
			year -> this.createPurchases(countries, year, customers)
		);
		
		this.customers.addAll(customers);
		customers.clear();
		this.storageManager.store(this.customers);
	}
	
	private void createPurchases(
		final List<CountryData> countries,
		final int year,
		final Set<Customer> customers
	)
	{
		this.logger().info("> purchases in " + year);
		
		final List<Purchase> purchases = countries.parallelStream()
			.flatMap(
				country -> country.shops.stream().flatMap(
					shop -> this.createPurchases(country, year, shop)
				)
			)
			.collect(toList());
				
		final Set<Customer> customersForYear = this.purchases.init(year, purchases, this.storageManager);

		this.logger().info("+ " + purchases.size() + " purchases in " + year);
		
		customers.addAll(customersForYear);
		
		customersForYear.clear();
		purchases.clear();
		
		this.gc();
	}
	
	private Stream<Purchase> createPurchases(
		final CountryData countryData,
		final int year,
		final Shop shop
	)
	{
		final List<Book>     books      = shop.inventory().books().collect(toList());
		final boolean        isLeapYear = Year.of(year).isLeap();
		final Random         random     = this.random;
		return shop.employees().flatMap(employee ->
			this.randomRange(this.dataAmount.maxPurchasesPerEmployeePerYear()).mapToObj(pi -> {
				final Customer customer = pi % 10 == 0
					? countryData.randomCustomer(random)
					: countryData.randomCustomer(random, shop.address().city());
				final long timestamp = this.randomDateTime(year, isLeapYear, random);
				final List<Purchase.Item> items = this.randomRange(this.dataAmount.maxItemsPerPurchase())
					.mapToObj(ii -> Purchase.Item.New(books.get(random.nextInt(books.size())), random.nextInt(3)))
					.collect(toList());
				return Purchase.New(shop, employee, customer, timestamp, items);
			})
		);
	}

	private long randomDateTime(
		final int year,
		final boolean isLeapYear,
		final Random random
	)
	{
		final Month month      = Month.of(random.nextInt(12) + 1);
		final int   dayOfMonth = random.nextInt(month.length(isLeapYear)) + 1;
		final int   hour       = 8 + random.nextInt(11);
		final int   minute     = random.nextInt(60);
		return LocalDateTime.of(year, month.getValue(), dayOfMonth, hour, minute)
			.toInstant(ZoneOffset.UTC).toEpochMilli();
	}
	
	private Book randomBook()
	{
		return this.bookList.get(this.random.nextInt(this.bookList.size()));
	}
	
	private List<Employee> createEmployees(
		final CountryData countryData,
		final City city
	)
	{
		return this.randomRange(this.dataAmount.maxEmployeesPerShop())
			.mapToObj(i -> Employee.New(
				countryData.faker.name().fullName(),
				this.createAddress(city, countryData.faker)
			))
			.collect(toList());
	}
	
	private Address createAddress(
		final City city,
		final Faker faker
	)
	{
		final com.github.javafaker.Address fa = faker.address();
		return Address.New(
			fa.streetAddress(),
			fa.secondaryAddress(),
			fa.zipCode(),
			city
		);
	}
	
	private double createPrice(
		final double min,
		final double max
	)
	{
		return min + (this.random.nextDouble() * (max - min));
	}
		
	private IntStream randomRange(
		final int upperBoundInclusive
	)
	{
		return IntStream.rangeClosed(0, this.randomMax(upperBoundInclusive));
	}
	
	private int randomMax(
		final int upperBoundInclusive
	)
	{
		int max = this.random.nextInt(upperBoundInclusive);
		final double minRatio;
		if((minRatio = this.dataAmount.minRatio()) > 0)
		{
			max = Math.max(max, (int)(upperBoundInclusive * minRatio));
		}
		return max;
	}
	
	private void gc()
	{
//		this.storageManager.persistenceManager().objectRegistry().clear();
//		this.storageManager.issueCacheCheck(Long.MAX_VALUE, (s, t, e) -> true);
		System.gc();
	}
	
}
